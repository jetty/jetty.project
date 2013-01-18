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

import java.util.Collections;
import java.util.Deque;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.util.Attributes;

public class HttpConversation implements Attributes
{
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    private final Deque<HttpExchange> exchanges = new ConcurrentLinkedDeque<>();
    private final HttpClient client;
    private final long id;
    private volatile List<Response.ResponseListener> listeners;

    public HttpConversation(HttpClient client, long id)
    {
        this.client = client;
        this.id = id;
    }

    public long id()
    {
        return id;
    }

    public Deque<HttpExchange> getExchanges()
    {
        return exchanges;
    }

    public List<Response.ResponseListener> getResponseListeners()
    {
        return listeners;
    }

    public void setResponseListeners(List<Response.ResponseListener> listeners)
    {
        this.listeners = listeners;
    }

    public void complete()
    {
        client.removeConversation(this);
    }

    @Override
    public Object getAttribute(String name)
    {
        return attributes.get(name);
    }

    @Override
    public void setAttribute(String name, Object attribute)
    {
        attributes.put(name, attribute);
    }

    @Override
    public void removeAttribute(String name)
    {
        attributes.remove(name);
    }

    @Override
    public Enumeration<String> getAttributeNames()
    {
        return Collections.enumeration(attributes.keySet());
    }

    @Override
    public void clearAttributes()
    {
        attributes.clear();
    }

    public boolean abort(Throwable cause)
    {
        HttpExchange exchange = exchanges.peekLast();
        return exchange != null && exchange.abort(cause);
    }

    @Override
    public String toString()
    {
        return String.format("%s[%d]", HttpConversation.class.getSimpleName(), id);
    }
}
