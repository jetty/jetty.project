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

package org.eclipse.jetty.http3.client.transport.internal;

import java.nio.channels.ClosedChannelException;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicMarkableReference;

import org.eclipse.jetty.client.Connection;
import org.eclipse.jetty.client.Destination;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.internal.HttpDestination;
import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.client.internal.HTTP3SessionClient;
import org.eclipse.jetty.http3.frames.SettingsFrame;
import org.eclipse.jetty.http3.internal.HTTP3Session;
import org.eclipse.jetty.util.Promise;

public class SessionClientListener implements Session.Client.Listener
{
    private final AtomicMarkableReference<HttpConnectionOverHTTP3> connection = new AtomicMarkableReference<>(null, false);
    private final Map<String, Object> context;

    public SessionClientListener(Map<String, Object> context)
    {
        this.context = context;
    }

    public void onConnect(Session.Client session, Throwable failure)
    {
        if (failure != null)
            failConnectionPromise(failure);
    }

    @Override
    public void onSettings(Session session, SettingsFrame frame)
    {
        HttpDestination destination = (HttpDestination)context.get(HttpClientTransport.HTTP_DESTINATION_CONTEXT_KEY);
        HttpConnectionOverHTTP3 connection = (HttpConnectionOverHTTP3)newConnection(destination, (HTTP3SessionClient)session);
        if (this.connection.compareAndSet(null, connection, false, true))
            httpConnectionPromise().succeeded(connection);
    }

    @Override
    public boolean onIdleTimeout(Session session)
    {
        long idleTimeout = ((HTTP3Session)session).getIdleTimeout();
        TimeoutException timeout = new TimeoutException("idle timeout expired: " + idleTimeout + " ms");
        if (failConnectionPromise(timeout))
            return true;
        HttpConnectionOverHTTP3 connection = this.connection.getReference();
        if (connection != null)
            return connection.onIdleTimeout(idleTimeout, timeout);
        return true;
    }

    @Override
    public void onDisconnect(Session session, long error, String reason)
    {
        onFailure(session, error, reason, new ClosedChannelException());
    }

    @Override
    public void onFailure(Session session, long error, String reason, Throwable failure)
    {
        if (failConnectionPromise(failure))
            return;
        HttpConnectionOverHTTP3 connection = this.connection.getReference();
        if (connection != null)
            connection.close(failure);
    }

    protected Connection newConnection(Destination destination, HTTP3SessionClient session)
    {
        return new HttpConnectionOverHTTP3(destination, session);
    }

    @SuppressWarnings("unchecked")
    private Promise<Connection> httpConnectionPromise()
    {
        return (Promise<Connection>)context.get(HttpClientTransport.HTTP_CONNECTION_PROMISE_CONTEXT_KEY);
    }

    private boolean failConnectionPromise(Throwable failure)
    {
        boolean result = connection.compareAndSet(null, null, false, true);
        if (result)
            httpConnectionPromise().failed(failure);
        return result;
    }
}
