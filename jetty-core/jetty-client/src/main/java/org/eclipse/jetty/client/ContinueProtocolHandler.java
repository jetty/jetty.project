//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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

import org.eclipse.jetty.client.internal.HttpContentResponse;
import org.eclipse.jetty.client.transport.HttpConversation;
import org.eclipse.jetty.client.transport.HttpExchange;
import org.eclipse.jetty.client.transport.HttpRequest;
import org.eclipse.jetty.client.transport.ResponseListeners;
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

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public boolean accept(Request request, Response response)
    {
        boolean handled100 = request.getAttributes().containsKey(ATTRIBUTE);
        if (handled100)
            return false;
        boolean is100 = response.getStatus() == HttpStatus.CONTINUE_100;
        if (is100)
            return true;
        // Also handle non-100 responses, because we need to complete the request to complete the whole exchange.
        return request.getHeaders().contains(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE.asString());
    }

    @Override
    public Response.Listener getResponseListener()
    {
        // Return new instances every time to keep track of the response content
        return new ContinueListener();
    }

    protected Runnable onContinue(Request request)
    {
        return null;
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
                Runnable proceedAction = onContinue(request);
                // Pass the proceed action to be executed
                // by the sender, not here by the receiver.
                exchange.proceed(proceedAction, null);
            }
            else
            {
                // Server either does not support 100 Continue,
                // or it does and wants to refuse the request content,
                // or we got some other HTTP status code like a redirect.
                ResponseListeners listeners = exchange.getResponseListeners();
                HttpContentResponse contentResponse = new HttpContentResponse(response, getContent(), getMediaType(), getEncoding());
                listeners.emitSuccess(contentResponse);
                exchange.proceed(null, new HttpRequestException("Expectation failed", request));
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
            ResponseListeners listeners = exchange.getResponseListeners();
            HttpContentResponse contentResponse = new HttpContentResponse(response, getContent(), getMediaType(), getEncoding());
            listeners.emitFailureComplete(new Result(exchange.getRequest(), exchange.getRequestFailure(), contentResponse, failure));
        }

        @Override
        public void onComplete(Result result)
        {
        }
    }
}
