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

package org.eclipse.jetty.websocket.core;

import java.io.IOException;
import java.net.URI;
import java.util.List;

import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiation;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiator;
import org.eclipse.jetty.websocket.core.server.WebSocketUpgradeHandler;

public class WebSocketServer
{
    private final Server server = new Server();
    private URI serverUri;

    public void start() throws Exception
    {
        server.start();
        serverUri = new URI("ws://localhost:" + getLocalPort());
    }

    public void stop() throws Exception
    {
        server.stop();
    }

    public int getLocalPort()
    {
        return server.getBean(NetworkConnector.class).getLocalPort();
    }

    public Server getServer()
    {
        return server;
    }

    public WebSocketServer(FrameHandler frameHandler)
    {
        this(new DefaultNegotiator(frameHandler), false);
    }

    public WebSocketServer(WebSocketNegotiator negotiator)
    {
        this(negotiator, false);
    }

    public WebSocketServer(FrameHandler frameHandler, boolean tls)
    {
        this(new DefaultNegotiator(frameHandler), tls);
    }

    public WebSocketServer(WebSocketNegotiator negotiator, boolean tls)
    {
        this(new WebSocketComponents(), negotiator, tls);
    }

    public WebSocketServer(WebSocketComponents components, WebSocketNegotiator negotiator, boolean tls)
    {
        ServerConnector connector;
        if (tls)
            connector = new ServerConnector(server, createServerSslContextFactory());
        else
            connector = new ServerConnector(server);
        server.addConnector(connector);

        ContextHandler context = new ContextHandler("/");
        server.setHandler(context);

        WebSocketUpgradeHandler upgradeHandler = new WebSocketUpgradeHandler(components);
        upgradeHandler.addMapping("/*", negotiator);
        context.setHandler(upgradeHandler);
    }

    private SslContextFactory.Server createServerSslContextFactory()
    {
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath("src/test/resources/keystore.p12");
        sslContextFactory.setKeyStorePassword("storepwd");
        return sslContextFactory;
    }

    public URI getUri()
    {
        return serverUri;
    }

    private static class DefaultNegotiator extends WebSocketNegotiator.AbstractNegotiator
    {
        private final FrameHandler frameHandler;

        public DefaultNegotiator(FrameHandler frameHandler)
        {
            this.frameHandler = frameHandler;
        }

        @Override
        public FrameHandler negotiate(WebSocketNegotiation negotiation) throws IOException
        {
            List<String> offeredSubprotocols = negotiation.getOfferedSubprotocols();
            if (!offeredSubprotocols.isEmpty())
                negotiation.setSubprotocol(offeredSubprotocols.get(0));

            return frameHandler;
        }
    }
}
