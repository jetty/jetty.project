//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;

public class RedirectProtocolHandler extends Response.Listener.Empty implements ProtocolHandler
{
    private static final String ATTRIBUTE = RedirectProtocolHandler.class.getName() + ".redirect";

    private final ResponseNotifier notifier = new ResponseNotifier();
    private final HttpClient client;

    public RedirectProtocolHandler(HttpClient client)
    {
        this.client = client;
    }

    @Override
    public boolean accept(Request request, Response response)
    {
        switch (response.status())
        {
            case 301:
            case 302:
            case 303:
            case 307:
                return request.followRedirects();
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
            String location = response.headers().get("location");
            int status = response.status();
            switch (status)
            {
                case 301:
                {
                    if (request.method() == HttpMethod.GET || request.method() == HttpMethod.HEAD)
                        redirect(result, request.method(), location);
                    else
                        fail(result, new HttpResponseException("HTTP protocol violation: received 301 for non GET or HEAD request", response));
                    break;
                }
                case 302:
                case 303:
                {
                    // Redirect must be done using GET
                    redirect(result, HttpMethod.GET, location);
                    break;
                }
                case 307:
                {
                    // Keep same method
                    redirect(result, request.method(), location);
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
            fail(result, result.getFailure());
        }
    }

    private void redirect(Result result, HttpMethod method, String location)
    {
        Request request = result.getRequest();
        HttpConversation conversation = client.getConversation(request);
        Integer redirects = (Integer)conversation.getAttribute(ATTRIBUTE);
        if (redirects == null)
            redirects = 0;

        if (redirects < client.getMaxRedirects())
        {
            ++redirects;
            conversation.setAttribute(ATTRIBUTE, redirects);

            Request redirect = client.newRequest(request.id(), location);

            // Use given method
            redirect.method(method);

            redirect.version(request.version());

            // Copy headers
            for (HttpFields.Field header : request.headers())
                redirect.header(header.getName(), header.getValue());

            // Copy content
            redirect.content(request.content());

            redirect.send(new Response.Listener.Empty());
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
        HttpConversation conversation = client.getConversation(request);
        Response.Listener listener = conversation.exchanges().peekFirst().listener();
        // TODO: should we reply all event, or just the failure ?
        notifier.notifyFailure(listener, response, failure);
        notifier.notifyComplete(listener, new Result(request, response, failure));
    }
}
