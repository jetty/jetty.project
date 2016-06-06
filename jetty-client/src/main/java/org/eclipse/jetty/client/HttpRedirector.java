//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.BufferingResponseListener;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * Utility class that handles HTTP redirects.
 * <p>
 * Applications can disable redirection via {@link Request#followRedirects(boolean)}
 * and then rely on this class to perform the redirect in a simpler way, for example:
 * <pre>
 * HttpRedirector redirector = new HttpRedirector(httpClient);
 *
 * Request request = httpClient.newRequest("http://host/path").followRedirects(false);
 * ContentResponse response = request.send();
 * while (redirector.isRedirect(response))
 * {
 *     // Validate the redirect URI
 *     if (!validate(redirector.extractRedirectURI(response)))
 *         break;
 *
 *     Result result = redirector.redirect(request, response);
 *     request = result.getRequest();
 *     response = result.getResponse();
 * }
 * </pre>
 */
public class HttpRedirector
{
    private static final Logger LOG = Log.getLogger(HttpRedirector.class);
    private static final String SCHEME_REGEXP = "(^https?)";
    private static final String AUTHORITY_REGEXP = "([^/\\?#]+)";
    // The location may be relative so the scheme://authority part may be missing
    private static final String DESTINATION_REGEXP = "(" + SCHEME_REGEXP + "://" + AUTHORITY_REGEXP + ")?";
    private static final String PATH_REGEXP = "([^\\?#]*)";
    private static final String QUERY_REGEXP = "([^#]*)";
    private static final String FRAGMENT_REGEXP = "(.*)";
    private static final Pattern URI_PATTERN = Pattern.compile(DESTINATION_REGEXP + PATH_REGEXP + QUERY_REGEXP + FRAGMENT_REGEXP);
    private static final String ATTRIBUTE = HttpRedirector.class.getName() + ".redirects";

    private final HttpClient client;
    private final ResponseNotifier notifier;

    public HttpRedirector(HttpClient client)
    {
        this.client = client;
        this.notifier = new ResponseNotifier();
    }

    /**
     * @param response the response to check for redirects
     * @return whether the response code is a HTTP redirect code
     */
    public boolean isRedirect(Response response)
    {
        switch (response.getStatus())
        {
            case 301:
            case 302:
            case 303:
            case 307:
            case 308:
                return true;
            default:
                return false;
        }
    }

    /**
     * Redirects the given {@code response}, blocking until the redirect is complete.
     *
     * @param request the original request that triggered the redirect
     * @param response the response to the original request
     * @return a {@link Result} object containing the request to the redirected location and its response
     * @throws InterruptedException if the thread is interrupted while waiting for the redirect to complete
     * @throws ExecutionException if the redirect failed
     * @see #redirect(Request, Response, Response.CompleteListener)
     */
    public Result redirect(Request request, Response response) throws InterruptedException, ExecutionException
    {
        final AtomicReference<Result> resultRef = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        Request redirect = redirect(request, response, new BufferingResponseListener()
        {
            @Override
            public void onComplete(Result result)
            {
                resultRef.set(new Result(result.getRequest(),
                        result.getRequestFailure(),
                        new HttpContentResponse(result.getResponse(), getContent(), getMediaType(), getEncoding()),
                        result.getResponseFailure()));
                latch.countDown();
            }
        });

        try
        {
            latch.await();
            Result result = resultRef.get();
            if (result.isFailed())
                throw new ExecutionException(result.getFailure());
            return result;
        }
        catch (InterruptedException x)
        {
            // If the application interrupts, we need to abort the redirect
            redirect.abort(x);
            throw x;
        }
    }

    /**
     * Redirects the given {@code response} asynchronously.
     *
     * @param request the original request that triggered the redirect
     * @param response the response to the original request
     * @param listener the listener that receives response events
     * @return the request to the redirected location
     */
    public Request redirect(Request request, Response response, Response.CompleteListener listener)
    {
        if (isRedirect(response))
        {
            String location = response.getHeaders().get("Location");
            URI newURI = extractRedirectURI(response);
            if (newURI != null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Redirecting to {} (Location: {})", newURI, location);
                return redirect(request, response, listener, newURI);
            }
            else
            {
                fail(request, response, new HttpResponseException("Invalid 'Location' header: " + location, response));
                return null;
            }
        }
        else
        {
            fail(request, response, new HttpResponseException("Cannot redirect: " + response, response));
            return null;
        }
    }

    /**
     * Extracts and sanitizes (by making it absolute and escaping paths and query parameters)
     * the redirect URI of the given {@code response}.
     *
     * @param response the response to extract the redirect URI from
     * @return the absolute redirect URI, or null if the response does not contain a valid redirect location
     */
    public URI extractRedirectURI(Response response)
    {
        String location = response.getHeaders().get("location");
        if (location != null)
            return sanitize(location);
        return null;
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

    private Request redirect(Request request, Response response, Response.CompleteListener listener, URI newURI)
    {
        if (!newURI.isAbsolute())
        {
            URI requestURI = request.getURI();
            if (requestURI == null)
            {
                String uri = request.getScheme() + "://" + request.getHost();
                int port = request.getPort();
                if (port > 0)
                    uri += ":" + port;
                requestURI = URI.create(uri);
            }
            newURI = requestURI.resolve(newURI);
        }

        int status = response.getStatus();
        switch (status)
        {
            case 301:
            {
                String method = request.getMethod();
                if (HttpMethod.GET.is(method) || HttpMethod.HEAD.is(method) || HttpMethod.PUT.is(method))
                    return redirect(request, response, listener, newURI, method);
                else if (HttpMethod.POST.is(method))
                    return redirect(request, response, listener, newURI, HttpMethod.GET.asString());
                fail(request, response, new HttpResponseException("HTTP protocol violation: received 301 for non GET/HEAD/POST/PUT request", response));
                return null;
            }
            case 302:
            {
                String method = request.getMethod();
                if (HttpMethod.HEAD.is(method) || HttpMethod.PUT.is(method))
                    return redirect(request, response, listener, newURI, method);
                else
                    return redirect(request, response, listener, newURI, HttpMethod.GET.asString());
            }
            case 303:
            {
                String method = request.getMethod();
                if (HttpMethod.HEAD.is(method))
                    return redirect(request, response, listener, newURI, method);
                else
                    return redirect(request, response, listener, newURI, HttpMethod.GET.asString());
            }
            case 307:
            case 308:
            {
                // Keep same method
                return redirect(request, response, listener, newURI, request.getMethod());
            }
            default:
            {
                fail(request, response, new HttpResponseException("Unhandled HTTP status code " + status, response));
                return null;
            }
        }
    }

    private Request redirect(Request request, Response response, Response.CompleteListener listener, URI location, String method)
    {
        HttpRequest httpRequest = (HttpRequest)request;
        HttpConversation conversation = httpRequest.getConversation();
        Integer redirects = (Integer)conversation.getAttribute(ATTRIBUTE);
        if (redirects == null)
            redirects = 0;
        if (redirects < client.getMaxRedirects())
        {
            ++redirects;
            conversation.setAttribute(ATTRIBUTE, redirects);
            return sendRedirect(httpRequest, response, listener, location, method);
        }
        else
        {
            fail(request, response, new HttpResponseException("Max redirects exceeded " + redirects, response));
            return null;
        }
    }

    private Request sendRedirect(final HttpRequest httpRequest, Response response, Response.CompleteListener listener, URI location, String method)
    {
        try
        {
            Request redirect = client.copyRequest(httpRequest, location);

            // Use given method
            redirect.method(method);

            redirect.onRequestBegin(new Request.BeginListener()
            {
                @Override
                public void onBegin(Request redirect)
                {
                    Throwable cause = httpRequest.getAbortCause();
                    if (cause != null)
                        redirect.abort(cause);
                }
            });

            redirect.send(listener);
            return redirect;
        }
        catch (Throwable x)
        {
            fail(httpRequest, response, x);
            return null;
        }
    }

    protected void fail(Request request, Response response, Throwable failure)
    {
        HttpConversation conversation = ((HttpRequest)request).getConversation();
        conversation.updateResponseListeners(null);
        List<Response.ResponseListener> listeners = conversation.getResponseListeners();
        notifier.notifyFailure(listeners, response, failure);
        notifier.notifyComplete(listeners, new Result(request, response, failure));
    }
}
