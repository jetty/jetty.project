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

import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.BufferingResponseListener;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;

/**
 * <p>A protocol handler that handles the 103 response code.</p>
 */
public class EarlyHintsProtocolHandler implements ProtocolHandler
{
    public static final String NAME = "early-hints";

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public boolean accept(Request request, Response response)
    {
        return response.getStatus() == HttpStatus.EARLY_HINT_103;
    }

    @Override
    public Response.Listener getResponseListener()
    {
        return new EarlyHintsListener();
    }

    protected void onEarlyHints(Request request, HttpFields responseHeaders)
    {
    }

    private class EarlyHintsListener extends BufferingResponseListener
    {
        private final ResponseNotifier notifier = new ResponseNotifier();

        @Override
        public void onSuccess(Response response)
        {
            Request request = response.getRequest();
            HttpConversation conversation = ((HttpRequest)request).getConversation();

            // Reset the conversation listeners, since we are going to receive another response code.
            conversation.updateResponseListeners(null);

            HttpExchange exchange = conversation.getExchanges().peekLast();
            assert exchange != null;

            HttpFields responseHeaders = HttpFields.build(response.getHeaders());
            exchange.resetResponse();
            onEarlyHints(request, responseHeaders);
        }

        @Override
        public void onFailure(Response response, Throwable failure)
        {
            HttpConversation conversation = ((HttpRequest)response.getRequest()).getConversation();
            // Reset the conversation listeners to allow the conversation to be completed.
            conversation.updateResponseListeners(null);

            HttpExchange exchange = conversation.getExchanges().peekLast();
            if (exchange != null)
            {
                List<Response.ResponseListener> listeners = exchange.getResponseListeners();
                HttpContentResponse contentResponse = new HttpContentResponse(response, getContent(), getMediaType(), getEncoding());
                notifier.forwardFailureComplete(listeners, exchange.getRequest(), exchange.getRequestFailure(), contentResponse, failure);
            }
        }

        @Override
        public void onComplete(Result result)
        {
        }
    }
}
