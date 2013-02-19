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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.util.AttributesMap;

public class HttpConversation extends AttributesMap
{
    private final Deque<HttpExchange> exchanges = new ConcurrentLinkedDeque<>();
    private final HttpClient client;
    private final long id;
    private volatile Response.ResponseListener listener;

    public HttpConversation(HttpClient client, long id)
    {
        this.client = client;
        this.id = id;
    }

    public long getID()
    {
        return id;
    }

    public Deque<HttpExchange> getExchanges()
    {
        return exchanges;
    }

    /**
     * Returns the list of response listeners that needs to be notified of response events.
     * This list changes as the conversation proceeds, as follows:
     * <ol>
     * <li>
     *     request R1 send => conversation.setResponseListener(null)
     *     <ul>
     *         <li>exchanges in conversation: E1</li>
     *         <li>listeners to be notified: E1.listeners</li>
     *     </ul>
     * </li>
     * <li>
     *     response R1 arrived, 401 => conversation.setResponseListener(AuthenticationProtocolHandler.listener)
     *     <ul>
     *         <li>exchanges in conversation: E1</li>
     *         <li>listeners to be notified: AuthenticationProtocolHandler.listener</li>
     *     </ul>
     * </li>
     * <li>
     *     request R2 send => conversation.setResponseListener(null)
     *     <ul>
     *         <li>exchanges in conversation: E1 + E2</li>
     *         <li>listeners to be notified: E2.listeners + E1.listeners</li>
     *     </ul>
     * </li>
     * <li>
     *     response R2 arrived, 302 => conversation.setResponseListener(RedirectProtocolHandler.listener)
     *     <ul>
     *         <li>exchanges in conversation: E1 + E2</li>
     *         <li>listeners to be notified: E2.listeners + RedirectProtocolHandler.listener</li>
     *     </ul>
     * </li>
     * <li>
     *     request R3 send => conversation.setResponseListener(null)
     *     <ul>
     *         <li>exchanges in conversation: E1 + E2 + E3</li>
     *         <li>listeners to be notified: E3.listeners + E1.listeners</li>
     *     </ul>
     * </li>
     * <li>
     *     response R3 arrived, 200 => conversation.setResponseListener(null)
     *     <ul>
     *         <li>exchanges in conversation: E1 + E2 + E3</li>
     *         <li>listeners to be notified: E3.listeners + E1.listeners</li>
     *     </ul>
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
        HttpExchange firstExchange = exchanges.peekFirst();
        HttpExchange lastExchange = exchanges.peekLast();
        if (firstExchange == lastExchange)
        {
            if (listener != null)
                return Arrays.asList(listener);
            else
                return firstExchange.getResponseListeners();
        }
        else
        {
            // Order is important, we want to notify the last exchange first
            List<Response.ResponseListener> result = new ArrayList<>(lastExchange.getResponseListeners());
            if (listener != null)
                result.add(listener);
            else
                result.addAll(firstExchange.getResponseListeners());
            return result;
        }
    }

    /**
     * Sets an override response listener that must be notified instead of the first exchange response listeners.
     * This works in conjunction with {@link #getResponseListeners()}, returning the appropriate response
     * listeners that needs to be notified of response events.
     *
     * @param listener the override response listener
     */
    public void setResponseListener(Response.ResponseListener listener)
    {
        this.listener = listener;
    }

    public void complete()
    {
        // The conversation is really terminated only
        // when there is no conversation listener that
        // may have continued the conversation.
        if (listener == null)
            client.removeConversation(this);
    }

    public boolean abort(Throwable cause)
    {
        HttpExchange exchange = exchanges.peekLast();
        return exchange.abort(cause);
    }

    @Override
    public String toString()
    {
        return String.format("%s[%d]", HttpConversation.class.getSimpleName(), id);
    }
}
