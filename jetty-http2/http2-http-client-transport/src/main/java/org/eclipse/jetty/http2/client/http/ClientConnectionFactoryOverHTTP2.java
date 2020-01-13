//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http2.client.http;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.HTTP2ClientConnectionFactory;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;

public class ClientConnectionFactoryOverHTTP2 implements ClientConnectionFactory
{
    private final ClientConnectionFactory factory = new HTTP2ClientConnectionFactory();
    private final HTTP2Client client;

    public ClientConnectionFactoryOverHTTP2(HTTP2Client client)
    {
        this.client = client;
    }

    @Override
    public Connection newConnection(EndPoint endPoint, Map<String, Object> context) throws IOException
    {
        HTTPSessionListenerPromise listenerPromise = new HTTPSessionListenerPromise(context);
        context.put(HTTP2ClientConnectionFactory.CLIENT_CONTEXT_KEY, client);
        context.put(HTTP2ClientConnectionFactory.SESSION_LISTENER_CONTEXT_KEY, listenerPromise);
        context.put(HTTP2ClientConnectionFactory.SESSION_PROMISE_CONTEXT_KEY, listenerPromise);
        return factory.newConnection(endPoint, context);
    }

    public static class H2 extends Info
    {
        public H2(HTTP2Client client)
        {
            super(List.of("h2"), new ClientConnectionFactoryOverHTTP2(client));
        }
    }

    public static class H2C extends Info
    {
        public H2C(HTTP2Client client)
        {
            super(List.of("h2c"), new ClientConnectionFactoryOverHTTP2(client));
        }
    }
}
