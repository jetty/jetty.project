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

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.util.AttributesMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpConversation extends AttributesMap
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpConversation.class);

    private final Deque<HttpExchange> exchanges = new ConcurrentLinkedDeque<>();
    private volatile List<Response.ResponseListener> listeners;

    public Deque<HttpExchange> getExchanges()
    {
        return exchanges;
    }

    /**
     * Returns the list of response listeners that needs to be notified of response events.
     * This list changes as the conversation proceeds, as follows:
     * <ol>
     * <li>
     * request R1 send =&gt; conversation.updateResponseListeners(null)
     * <ul>
     * <li>exchanges in conversation: E1</li>
     * <li>listeners to be notified: E1.listeners</li>
     * </ul>
     * </li>
     * <li>
     * response R1 arrived, 401 =&gt; conversation.updateResponseListeners(AuthenticationProtocolHandler.listener)
     * <ul>
     * <li>exchanges in conversation: E1</li>
     * <li>listeners to be notified: AuthenticationProtocolHandler.listener</li>
     * </ul>
     * </li>
     * <li>
     * request R2 send =&gt; conversation.updateResponseListeners(null)
     * <ul>
     * <li>exchanges in conversation: E1 + E2</li>
     * <li>listeners to be notified: E2.listeners + E1.listeners</li>
     * </ul>
     * </li>
     * <li>
     * response R2 arrived, 302 =&gt; conversation.updateResponseListeners(RedirectProtocolHandler.listener)
     * <ul>
     * <li>exchanges in conversation: E1 + E2</li>
     * <li>listeners to be notified: E2.listeners + RedirectProtocolHandler.listener</li>
     * </ul>
     * </li>
     * <li>
     * request R3 send =&gt; conversation.updateResponseListeners(null)
     * <ul>
     * <li>exchanges in conversation: E1 + E2 + E3</li>
     * <li>listeners to be notified: E3.listeners + E1.listeners</li>
     * </ul>
     * </li>
     * <li>
     * response R3 arrived, 200 =&gt; conversation.updateResponseListeners(null)
     * <ul>
     * <li>exchanges in conversation: E1 + E2 + E3</li>
     * <li>listeners to be notified: E3.listeners + E1.listeners</li>
     * </ul>
     * </li>
     * </ol>
     * Basically the override conversation listener replaces the first exchange response listener,
     * and we also notify the last exchange response listeners (if it's not also the first).
     *
     * This scheme allows for protocol handlers to not worry about other protocol handlers, or to worry
     * too much about notifying the first exchange response listeners, but still allowing a protocol
     * handler to perform completion activities while another protocol handler performs new ones (as an
     * example, the {@link AuthenticationProtocolHandler} stores the successful authentication credentials
     * while the {@link RedirectProtocolHandler} performs a redirect).
     *
     * @return the list of response listeners that needs to be notified of response events
     */
    public List<Response.ResponseListener> getResponseListeners()
    {
        return listeners;
    }

    /**
     * Requests to update the response listener, eventually using the given override response listener,
     * that must be notified instead of the first exchange response listeners.
     * This works in conjunction with {@link #getResponseListeners()}, returning the appropriate response
     * listeners that needs to be notified of response events.
     *
     * @param overrideListener the override response listener
     */
    public void updateResponseListeners(Response.ResponseListener overrideListener)
    {
        // Create a new instance to avoid that iterating over the listeners
        // will notify a listener that may send a new request and trigger
        // another call to this method which will build different listeners
        // which may be iterated over when the iteration continues.
        HttpExchange firstExchange = exchanges.peekFirst();
        HttpExchange lastExchange = exchanges.peekLast();
        List<Response.ResponseListener> listeners = new ArrayList<>(firstExchange.getResponseListeners().size() + lastExchange.getResponseListeners().size());
        if (firstExchange == lastExchange)
        {
            // We don't have a conversation, just a single request.
            if (overrideListener != null)
                listeners.add(overrideListener);
            else
                listeners.addAll(firstExchange.getResponseListeners());
        }
        else
        {
            // We have a conversation (e.g. redirect, authentication).
            // Order is important, we want to notify the last exchange first.
            listeners.addAll(lastExchange.getResponseListeners());
            if (overrideListener != null)
                listeners.add(overrideListener);
            else
                listeners.addAll(firstExchange.getResponseListeners());
        }
        if (LOG.isDebugEnabled())
            LOG.debug("Exchanges in conversation {}, override={}, listeners={}", exchanges.size(), overrideListener, listeners);
        this.listeners = listeners;
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

    public boolean abort(Throwable cause)
    {
        HttpExchange exchange = exchanges.peekLast();
        return exchange != null && exchange.abort(cause);
    }

    @Override
    public String toString()
    {
        return String.format("%s[%x]", HttpConversation.class.getSimpleName(), hashCode());
    }
}
