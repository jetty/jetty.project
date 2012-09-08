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

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.eclipse.jetty.client.api.Response;

public class HttpConversation
{
    private final Queue<HttpExchange> exchanges = new ConcurrentLinkedQueue<>();
    private final HttpClient client;
    private final long id;
    private volatile Response.Listener listener;

    public HttpConversation(HttpClient client, long id)
    {
        this.client = client;
        this.id = id;
    }

    public long id()
    {
        return id;
    }

    public Response.Listener listener()
    {
        return listener;
    }

    public void listener(Response.Listener listener)
    {
        this.listener = listener;
    }

    public void add(HttpExchange exchange)
    {
        exchanges.offer(exchange);
    }

    public HttpExchange first()
    {
        return exchanges.peek();
    }

    public void complete()
    {
        client.removeConversation(this);
    }

    @Override
    public String toString()
    {
        return String.format("%s[%d]", HttpConversation.class.getSimpleName(), id);
    }
}
