//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.client;

import java.util.List;

import org.eclipse.jetty.client.internal.HttpContentResponse;
import org.eclipse.jetty.client.internal.HttpConversation;
import org.eclipse.jetty.client.internal.HttpExchange;
import org.eclipse.jetty.client.internal.HttpRequest;
import org.eclipse.jetty.client.internal.ResponseNotifier;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpStatus;

/**
 * <p>A protocol handler that handles the 100 response code.</p>
 */
public class ContinueProtocolHandler implements ProtocolHandler
{
    public static final String NAME = "continue";
    private static final String ATTRIBUTE = ContinueProtocolHandler.class.getName() + ".100continue";

    private final ResponseNotifier notifier;

    public ContinueProtocolHandler()
    {
        this.notifier = new ResponseNotifier();
    }

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public boolean accept(Request request, Response response)
    {
        boolean is100 = response.getStatus() == HttpStatus.CONTINUE_100;
        boolean expect100 = request.getHeaders().contains(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE.asString());
        boolean handled100 = request.getAttributes().containsKey(ATTRIBUTE);
        return (is100 || expect100) && !handled100;
    }

    @Override
    public Response.Listener getResponseListener()
    {
        // Return new instances every time to keep track of the response content
        return new ContinueListener();
    }

    protected void onContinue(Request request)
    {
    }

    protected class ContinueListener extends BufferingResponseListener
    {
        @Override
        public void onSuccess(Response response)
        {
            // Handling of success must be done here and not from onComplete(),
            // since the onComplete() is not invoked because the request is not completed yet.

            Request request = response.getRequest();
            HttpConversation conversation = ((HttpRequest)request).getConversation();
            // Mark the 100 Continue response as handled
            request.attribute(ATTRIBUTE, Boolean.TRUE);

            // Reset the conversation listeners, since we are going to receive another response code
            conversation.updateResponseListeners(null);

            HttpExchange exchange = conversation.getExchanges().peekLast();
            assert exchange != null;

            if (response.getStatus() == HttpStatus.CONTINUE_100)
            {
                // All good, continue.
                exchange.resetResponse();
                exchange.proceed(null);
                onContinue(request);
            }
            else
            {
                // Server either does not support 100 Continue,
                // or it does and wants to refuse the request content,
                // or we got some other HTTP status code like a redirect.
                List<Response.ResponseListener> listeners = exchange.getResponseListeners();
                HttpContentResponse contentResponse = new HttpContentResponse(response, getContent(), getMediaType(), getEncoding());
                notifier.forwardSuccess(listeners, contentResponse);
                exchange.proceed(new HttpRequestException("Expectation failed", request));
            }
        }

        @Override
        public void onFailure(Response response, Throwable failure)
        {
            HttpConversation conversation = ((HttpRequest)response.getRequest()).getConversation();
            // Mark the 100 Continue response as handled
            conversation.setAttribute(ATTRIBUTE, Boolean.TRUE);
            // Reset the conversation listeners to allow the conversation to be completed
            conversation.updateResponseListeners(null);

            HttpExchange exchange = conversation.getExchanges().peekLast();
            assert exchange.getResponse() == response;
            List<Response.ResponseListener> listeners = exchange.getResponseListeners();
            HttpContentResponse contentResponse = new HttpContentResponse(response, getContent(), getMediaType(), getEncoding());
            notifier.forwardFailureComplete(listeners, exchange.getRequest(), exchange.getRequestFailure(), contentResponse, failure);
        }

        @Override
        public void onComplete(Result result)
        {
        }
    }
}
