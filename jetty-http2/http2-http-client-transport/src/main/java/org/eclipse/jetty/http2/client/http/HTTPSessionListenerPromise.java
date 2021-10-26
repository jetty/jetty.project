//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http2.client.http;

import java.nio.channels.ClosedChannelException;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicMarkableReference;

import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.http2.HTTP2Session;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.frames.GoAwayFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.util.Promise;

class HTTPSessionListenerPromise extends Session.Listener.Adapter implements Promise<Session>
{
    private final AtomicMarkableReference<HttpConnectionOverHTTP2> connection = new AtomicMarkableReference<>(null, false);
    private final Map<String, Object> context;

    HTTPSessionListenerPromise(Map<String, Object> context)
    {
        this.context = context;
    }

    @Override
    public void succeeded(Session session)
    {
        // This method is invoked when the client preface
        // is sent, but we want to succeed the nested
        // promise when the server preface is received.
    }

    @Override
    public void failed(Throwable failure)
    {
        failConnectionPromise(failure);
    }

    private HttpDestination destination()
    {
        return (HttpDestination)context.get(HttpClientTransport.HTTP_DESTINATION_CONTEXT_KEY);
    }

    @SuppressWarnings("unchecked")
    private Promise<Connection> httpConnectionPromise()
    {
        return (Promise<Connection>)context.get(HttpClientTransport.HTTP_CONNECTION_PROMISE_CONTEXT_KEY);
    }

    @Override
    public void onSettings(Session session, SettingsFrame frame)
    {
        if (!connection.isMarked())
            onServerPreface(session);
    }

    private void onServerPreface(Session session)
    {
        HttpConnectionOverHTTP2 connection = newHttpConnection(destination(), session);
        if (this.connection.compareAndSet(null, connection, false, true))
            httpConnectionPromise().succeeded(connection);
    }

    protected HttpConnectionOverHTTP2 newHttpConnection(HttpDestination destination, Session session)
    {
        return new HttpConnectionOverHTTP2(destination, session);
    }

    @Override
    public void onClose(Session session, GoAwayFrame frame)
    {
        if (failConnectionPromise(new ClosedChannelException()))
            return;
        HttpConnectionOverHTTP2 connection = this.connection.getReference();
        if (connection != null)
            onClose(connection, frame);
    }

    void onClose(HttpConnectionOverHTTP2 connection, GoAwayFrame frame)
    {
        connection.close();
    }

    @Override
    public boolean onIdleTimeout(Session session)
    {
        long idleTimeout = ((HTTP2Session)session).getEndPoint().getIdleTimeout();
        TimeoutException failure = new TimeoutException("Idle timeout expired: " + idleTimeout + " ms");
        if (failConnectionPromise(failure))
            return true;
        HttpConnectionOverHTTP2 connection = this.connection.getReference();
        if (connection != null)
            return connection.onIdleTimeout(idleTimeout, failure);
        return true;
    }

    @Override
    public void onFailure(Session session, Throwable failure)
    {
        if (failConnectionPromise(failure))
            return;
        HttpConnectionOverHTTP2 connection = this.connection.getReference();
        if (connection != null)
            connection.close(failure);
    }

    private boolean failConnectionPromise(Throwable failure)
    {
        boolean result = connection.compareAndSet(null, null, false, true);
        if (result)
            httpConnectionPromise().failed(failure);
        return result;
    }
}
