//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.core;

import java.io.IOException;
import java.util.List;

import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.server.Negotiation;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiator;
import org.eclipse.jetty.websocket.core.server.WebSocketUpgradeHandler;

public class WebSocketServer
{
    private static Logger LOG = Log.getLogger(WebSocketServer.class);
    private final Server server;

    public void start() throws Exception
    {
        server.start();
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
        this(new DefaultNegotiator(frameHandler));
    }

    public WebSocketServer(WebSocketNegotiator negotiator)
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        ContextHandler context = new ContextHandler("/");
        server.setHandler(context);

        WebSocketUpgradeHandler upgradeHandler = new WebSocketUpgradeHandler(negotiator);
        context.setHandler(upgradeHandler);
    }

    private static class DefaultNegotiator extends WebSocketNegotiator.AbstractNegotiator
    {
        private final FrameHandler frameHandler;

        public DefaultNegotiator(FrameHandler frameHandler)
        {
            this.frameHandler = frameHandler;
        }

        @Override
        public FrameHandler negotiate(Negotiation negotiation) throws IOException
        {
            List<String> offeredSubprotocols = negotiation.getOfferedSubprotocols();
            if (!offeredSubprotocols.isEmpty())
                negotiation.setSubprotocol(offeredSubprotocols.get(0));

            return frameHandler;
        }
    }
}
