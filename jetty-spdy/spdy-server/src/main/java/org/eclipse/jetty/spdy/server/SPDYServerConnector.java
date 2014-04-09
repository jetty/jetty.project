//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.spdy.server;

import java.util.Objects;

import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.NegotiatingServerConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.eclipse.jetty.util.ssl.SslContextFactory;

public class SPDYServerConnector extends ServerConnector
{
    public SPDYServerConnector(Server server, ServerSessionFrameListener listener)
    {
        super(server, (SslContextFactory)null, new SPDYServerConnectionFactory(SPDY.V2, listener));
    }

    public SPDYServerConnector(Server server, SslContextFactory sslContextFactory, ServerSessionFrameListener listener)
    {
        this(server, sslContextFactory, listener, new NPNServerConnectionFactory("spdy/3", "spdy/2", "http/1.1"));
    }

    public SPDYServerConnector(Server server, SslContextFactory sslContextFactory, ServerSessionFrameListener listener, NegotiatingServerConnectionFactory negotiator)
    {
        super(server, Objects.requireNonNull(sslContextFactory),
                negotiator,
                new SPDYServerConnectionFactory(SPDY.V3, listener),
                new SPDYServerConnectionFactory(SPDY.V2, listener),
                new HttpConnectionFactory());
        negotiator.setDefaultProtocol("http/1.1");
    }
}
