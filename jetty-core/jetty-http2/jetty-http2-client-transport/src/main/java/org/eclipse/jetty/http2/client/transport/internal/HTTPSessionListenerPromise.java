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

package org.eclipse.jetty.http2.client.transport.internal;

import java.nio.channels.ClosedChannelException;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicMarkableReference;

import org.eclipse.jetty.client.AbstractConnectionPool;
import org.eclipse.jetty.client.Connection;
import org.eclipse.jetty.client.ConnectionPool;
import org.eclipse.jetty.client.Destination;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.http2.HTTP2Connection;
import org.eclipse.jetty.http2.HTTP2Session;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.frames.GoAwayFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;

public class HTTPSessionListenerPromise implements Session.Listener, Promise<Session>
{
    private final AtomicMarkableReference<HttpConnectionOverHTTP2> connection = new AtomicMarkableReference<>(null, false);
    private final Map<String, Object> context;

    public HTTPSessionListenerPromise(Map<String, Object> context)
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

    private Destination destination()
    {
        return (Destination)context.get(HttpClientTransport.HTTP_DESTINATION_CONTEXT_KEY);
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
        Destination destination = destination();
        HTTP2Connection http2Connection = (HTTP2Connection)context.get(HTTP2Connection.class.getName());
        HttpConnectionOverHTTP2 connection = (HttpConnectionOverHTTP2)newConnection(destination, session, http2Connection);
        if (this.connection.compareAndSet(null, connection, false, true))
        {
            ConnectionPool connectionPool = destination.getConnectionPool();
            if (connectionPool instanceof AbstractConnectionPool pool)
                connection.setMaxUsage(pool.getMaxUsage());

            // The connection promise must be called synchronously
            // so that the HTTP/1 to HTTP/2 upgrade can create the
            // HTTP/2 stream that represents the HTTP/1 request.
            httpConnectionPromise().succeeded(connection);
        }
    }

    protected Connection newConnection(Destination destination, Session session, HTTP2Connection connection)
    {
        return new HttpConnectionOverHTTP2(destination, session, connection);
    }

    @Override
    public void onGoAway(Session session, GoAwayFrame frame)
    {
        if (!failConnectionPromise(new ClosedChannelException()))
        {
            HttpConnectionOverHTTP2 connection = getConnection();
            if (connection != null)
                connection.remove();
        }
    }

    @Override
    public void onClose(Session session, GoAwayFrame frame, Callback callback)
    {
        if (!failConnectionPromise(new ClosedChannelException()))
        {
            HttpConnectionOverHTTP2 connection = getConnection();
            if (connection != null)
                onClose(connection, frame);
        }
        callback.succeeded();
    }

    public void onClose(HttpConnectionOverHTTP2 connection, GoAwayFrame frame)
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
        HttpConnectionOverHTTP2 connection = getConnection();
        if (connection != null)
            return connection.onIdleTimeout(idleTimeout, failure);
        return true;
    }

    @Override
    public void onFailure(Session session, Throwable failure, Callback callback)
    {
        if (failConnectionPromise(failure))
        {
            callback.succeeded();
            return;
        }

        HttpConnectionOverHTTP2 connection = getConnection();
        if (connection == null)
        {
            callback.succeeded();
            return;
        }

        connection.onFailure(failure, callback);
    }

    private boolean failConnectionPromise(Throwable failure)
    {
        boolean result = connection.compareAndSet(null, null, false, true);
        if (result)
            httpConnectionPromise().failed(failure);
        return result;
    }

    private HttpConnectionOverHTTP2 getConnection()
    {
        return connection.getReference();
    }
}
