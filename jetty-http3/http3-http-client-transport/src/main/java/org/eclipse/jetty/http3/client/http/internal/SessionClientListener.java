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

package org.eclipse.jetty.http3.client.http.internal;

import java.util.Map;

import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.client.internal.HTTP3SessionClient;
import org.eclipse.jetty.http3.frames.SettingsFrame;
import org.eclipse.jetty.util.Promise;

public class SessionClientListener implements Session.Client.Listener
{
    private final Map<String, Object> context;

    public SessionClientListener(Map<String, Object> context)
    {
        this.context = context;
    }

    @SuppressWarnings("unchecked")
    private Promise<Connection> httpConnectionPromise()
    {
        return (Promise<Connection>)context.get(HttpClientTransport.HTTP_CONNECTION_PROMISE_CONTEXT_KEY);
    }

    @Override
    public void onSettings(Session session, SettingsFrame frame)
    {
        HttpDestination destination = (HttpDestination)context.get(HttpClientTransport.HTTP_DESTINATION_CONTEXT_KEY);
        HttpConnectionOverHTTP3 connection = new HttpConnectionOverHTTP3(destination, (HTTP3SessionClient)session);
        httpConnectionPromise().succeeded(connection);
    }
}
