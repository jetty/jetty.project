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

package org.eclipse.jetty.client.transport;

import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.eclipse.jetty.client.AuthenticationProtocolHandler;
import org.eclipse.jetty.client.RedirectProtocolHandler;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.client.Result;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpConversation extends Attributes.Lazy
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpConversation.class);

    private final Deque<HttpExchange> exchanges = new ConcurrentLinkedDeque<>();
    private volatile ResponseListeners listeners;

    public void offerExchange(HttpExchange exchange)
    {
        exchanges.offer(exchange);
    }

    private HttpExchange firstExchange()
    {
        HttpExchange exchange = exchanges.peekFirst();
        assert exchange != null;
        return exchange;
    }

    public HttpExchange lastExchange()
    {
        HttpExchange exchange = exchanges.peekLast();
        assert exchange != null;
        return exchange;
    }

    /**
     * <p>Updates the response listeners, eventually using the given override response listener,
     * that must be notified instead of the response listeners of the first exchange.</p>
     * <p>The response listeners change as the conversation proceeds, as follows:</p>
     * <ol>
     * <li>
     * request R1 send {@code => conversation.updateResponseListeners(null)}
     * <ul>
     * <li>exchanges in conversation: E1</li>
     * <li>listeners to be notified: E1.listeners</li>
     * </ul>
     * </li>
     * <li>
     * response R1 arrived, 401 {@code => conversation.updateResponseListeners(AuthenticationProtocolHandler.listener)}
     * <ul>
     * <li>exchanges in conversation: E1</li>
     * <li>listeners to be notified: AuthenticationProtocolHandler.listener</li>
     * </ul>
     * </li>
     * <li>
     * request R2 send {@code => conversation.updateResponseListeners(null)}
     * <ul>
     * <li>exchanges in conversation: E1 + E2</li>
     * <li>listeners to be notified: E2.listeners + E1.listeners</li>
     * </ul>
     * </li>
     * <li>
     * response R2 arrived, 302 {@code => conversation.updateResponseListeners(RedirectProtocolHandler.listener)}
     * <ul>
     * <li>exchanges in conversation: E1 + E2</li>
     * <li>listeners to be notified: E2.listeners + RedirectProtocolHandler.listener</li>
     * </ul>
     * </li>
     * <li>
     * request R3 send {@code => conversation.updateResponseListeners(null)}
     * <ul>
     * <li>exchanges in conversation: E1 + E2 + E3</li>
     * <li>listeners to be notified: E3.listeners + E1.listeners</li>
     * </ul>
     * </li>
     * <li>
     * response R3 arrived, 200 {@code => conversation.updateResponseListeners(null)}
     * <ul>
     * <li>exchanges in conversation: E1 + E2 + E3</li>
     * <li>listeners to be notified: E3.listeners + E1.listeners</li>
     * </ul>
     * </li>
     * </ol>
     * <p>Basically the override conversation listener replaces the first exchange response listener,
     * and we also notify the last exchange response listeners (if it's not also the first).</p>
     * <p>This scheme allows for protocol handlers to not worry about other protocol handlers, or to worry
     * too much about notifying the first exchange response listeners, but still allowing a protocol
     * handler to perform completion activities while another protocol handler performs new ones (as an
     * example, the {@link AuthenticationProtocolHandler} stores the successful authentication credentials
     * while the {@link RedirectProtocolHandler} performs a redirect).</p>
     *
     * @param overrideListener the override response listener
     */
    public void updateResponseListeners(Response.Listener overrideListener)
    {
        HttpExchange firstExchange = firstExchange();
        HttpExchange lastExchange = lastExchange();

        ResponseListeners listeners;
        if (firstExchange == lastExchange)
        {
            // We don't have a conversation, just a single request.
            if (overrideListener != null)
                listeners = new ResponseListeners(firstExchange.getRequest(), overrideListener);
            else
                listeners = firstExchange.getResponseListeners();
        }
        else
        {
            // We have a conversation (e.g. redirect, authentication).
            // Order is important, we want to notify the last exchange first.
            listeners = lastExchange.getResponseListeners().copy();
            if (overrideListener != null)
                listeners.addListener(overrideListener);
            else
                listeners.combine(firstExchange.getResponseListeners());
        }
        if (LOG.isDebugEnabled())
            LOG.debug("Exchanges in conversation {}, override={}, listeners={}", exchanges.size(), overrideListener, listeners);
        this.listeners = listeners;
    }

    public void notifyBegin(Response response)
    {
        listeners.notifyBegin(response);
    }

    public boolean notifyHeader(Response response, HttpField field)
    {
        return listeners.notifyHeader(response, field);
    }

    public void notifyHeaders(Response response)
    {
        listeners.notifyHeaders(response);
    }

    public void notifyContentSource(Response response, Content.Source source)
    {
        listeners.notifyContentSource(response, source);
    }

    public void notifySuccess(Response response)
    {
        listeners.notifySuccess(response);
    }

    public void notifyFailure(Response response, Throwable failure)
    {
        listeners.notifyFailure(response, failure);
    }

    public void notifyComplete(Result result)
    {
        listeners.notifyComplete(result);
    }

    public void emitSuccess(Response response)
    {
        listeners.emitSuccess(response);
    }

    public void emitSuccessComplete(Result result)
    {
        listeners.emitSuccessComplete(result);
    }

    public void emitFailureComplete(Result result)
    {
        listeners.emitFailureComplete(result);
    }

    /**
     * <p>Returns the total timeout for the conversation.</p>
     * <p>The conversation total timeout is the total timeout
     * of the first request in the conversation.</p>
     *
     * @return the total timeout of the conversation
     * @see Request#getTimeout()
     */
    public long getTimeout()
    {
        HttpExchange firstExchange = exchanges.peekFirst();
        return firstExchange == null ? 0 : firstExchange.getRequest().getTimeout();
    }

    public void abort(Throwable cause, Promise<Boolean> promise)
    {
        HttpExchange exchange = exchanges.peekLast();
        if (exchange != null)
            exchange.abort(cause, promise);
        else
            promise.succeeded(false);
    }

    @Override
    public String toString()
    {
        return String.format("%s[%x]", HttpConversation.class.getSimpleName(), hashCode());
    }
}
