//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.client;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class RedirectProtocolHandler extends Response.Listener.Empty implements ProtocolHandler
{
    private static final Logger LOG = Log.getLogger(RedirectProtocolHandler.class);
    private static final String SCHEME_REGEXP = "(^https?)";
    private static final String AUTHORITY_REGEXP = "([^/\\?#]+)";
    // The location may be relative so the scheme://authority part may be missing
    private static final String DESTINATION_REGEXP = "(" + SCHEME_REGEXP + "://" + AUTHORITY_REGEXP + ")?";
    private static final String PATH_REGEXP = "([^\\?#]*)";
    private static final String QUERY_REGEXP = "([^#]*)";
    private static final String FRAGMENT_REGEXP = "(.*)";
    private static final Pattern URI_PATTERN = Pattern.compile(DESTINATION_REGEXP + PATH_REGEXP + QUERY_REGEXP + FRAGMENT_REGEXP);
    private static final String ATTRIBUTE = RedirectProtocolHandler.class.getName() + ".redirects";

    private final HttpClient client;
    private final ResponseNotifier notifier;

    public RedirectProtocolHandler(HttpClient client)
    {
        this.client = client;
        this.notifier = new ResponseNotifier(client);
    }

    @Override
    public boolean accept(Request request, Response response)
    {
        switch (response.getStatus())
        {
            case 301:
            case 302:
            case 303:
            case 307:
                return request.isFollowRedirects();
        }
        return false;
    }

    @Override
    public Response.Listener getResponseListener()
    {
        return this;
    }

    @Override
    public void onComplete(Result result)
    {
        if (!result.isFailed())
        {
            Request request = result.getRequest();
            Response response = result.getResponse();
            String location = response.getHeaders().get("location");
            if (location != null)
            {
                URI newURI = sanitize(location);
                LOG.debug("Redirecting to {} (Location: {})", newURI, location);
                if (newURI != null)
                {
                    if (!newURI.isAbsolute())
                        newURI = request.getURI().resolve(newURI);

                    int status = response.getStatus();
                    switch (status)
                    {
                        case 301:
                        {
                            String method = request.getMethod();
                            if (HttpMethod.GET.is(method) || HttpMethod.HEAD.is(method))
                                redirect(result, method, newURI);
                            else
                                fail(result, new HttpResponseException("HTTP protocol violation: received 301 for non GET or HEAD request", response));
                            break;
                        }
                        case 302:
                        case 303:
                        {
                            // Redirect must be done using GET
                            redirect(result, HttpMethod.GET.asString(), newURI);
                            break;
                        }
                        case 307:
                        {
                            // Keep same method
                            redirect(result, request.getMethod(), newURI);
                            break;
                        }
                        default:
                        {
                            fail(result, new HttpResponseException("Unhandled HTTP status code " + status, response));
                            break;
                        }
                    }
                }
                else
                {
                    fail(result, new HttpResponseException("Malformed Location header " + location, response));
                }
            }
            else
            {
                fail(result, new HttpResponseException("Missing Location header " + location, response));
            }
        }
        else
        {
            fail(result, result.getFailure());
        }
    }

    private URI sanitize(String location)
    {
        // Redirects should be valid, absolute, URIs, with properly escaped paths and encoded
        // query parameters. However, shit happens, and here we try our best to recover.

        try
        {
            // Direct hit first: if passes, we're good
            return new URI(location);
        }
        catch (URISyntaxException x)
        {
            Matcher matcher = URI_PATTERN.matcher(location);
            if (matcher.matches())
            {
                String scheme = matcher.group(2);
                String authority = matcher.group(3);
                String path = matcher.group(4);
                String query = matcher.group(5);
                if (query.length() == 0)
                    query = null;
                String fragment = matcher.group(6);
                if (fragment.length() == 0)
                    fragment = null;
                try
                {
                    return new URI(scheme, authority, path, query, fragment);
                }
                catch (URISyntaxException xx)
                {
                    // Give up
                }
            }
            return null;
        }
    }

    private void redirect(Result result, String method, URI location)
    {
        final Request request = result.getRequest();
        HttpConversation conversation = client.getConversation(request.getConversationID(), false);
        Integer redirects = (Integer)conversation.getAttribute(ATTRIBUTE);
        if (redirects == null)
            redirects = 0;

        if (redirects < client.getMaxRedirects())
        {
            ++redirects;
            conversation.setAttribute(ATTRIBUTE, redirects);

            Request redirect = client.copyRequest(request, location);

            // Use given method
            redirect.method(method);

            redirect.onRequestBegin(new Request.BeginListener()
            {
                @Override
                public void onBegin(Request redirect)
                {
                    Throwable cause = request.getAbortCause();
                    if (cause != null)
                        redirect.abort(cause);
                }
            });

            redirect.send(null);
        }
        else
        {
            fail(result, new HttpResponseException("Max redirects exceeded " + redirects, result.getResponse()));
        }
    }

    private void fail(Result result, Throwable failure)
    {
        Request request = result.getRequest();
        Response response = result.getResponse();
        HttpConversation conversation = client.getConversation(request.getConversationID(), false);
        conversation.updateResponseListeners(null);
        List<Response.ResponseListener> listeners = conversation.getResponseListeners();
        notifier.notifyFailure(listeners, response, failure);
        notifier.notifyComplete(listeners, new Result(request, response, failure));
    }
}
