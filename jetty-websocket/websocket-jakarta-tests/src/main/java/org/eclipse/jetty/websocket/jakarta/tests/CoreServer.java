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

package org.eclipse.jetty.websocket.jakarta.tests;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.function.Function;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.core.Configuration;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.WebSocketExtensionRegistry;
import org.eclipse.jetty.websocket.core.server.Negotiation;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiator;
import org.eclipse.jetty.websocket.core.server.WebSocketUpgradeHandler;
import org.eclipse.jetty.websocket.jakarta.tests.framehandlers.FrameEcho;
import org.eclipse.jetty.websocket.jakarta.tests.framehandlers.WholeMessageEcho;

public class CoreServer extends ContainerLifeCycle
{
    private Server server;
    private ServerConnector connector;
    private WebSocketNegotiator negotiator;
    private URI serverUri;
    private URI wsUri;

    public CoreServer(Function<Negotiation, FrameHandler> negotiationFunction)
    {
        this(new BaseNegotiator()
        {
            @Override
            public FrameHandler negotiate(Negotiation negotiation) throws IOException
            {
                return negotiationFunction.apply(negotiation);
            }
        });
    }

    public CoreServer(WebSocketNegotiator negotiator)
    {
        this.negotiator = negotiator;
    }

    @Override
    protected void doStart() throws Exception
    {
        QueuedThreadPool threadPool = new QueuedThreadPool();
        threadPool.setName("qtp-CoreServer");

        server = new Server(threadPool);

        // Basic HTTP connector
        connector = new ServerConnector(server);

        // Add network connector
        server.addConnector(connector);

        // Add Handler
        HandlerList handlers = new HandlerList();
        handlers.addHandler(new WebSocketUpgradeHandler(negotiator));
        handlers.addHandler(new DefaultHandler());
        server.setHandler(handlers);

        // Start Server
        addBean(server);

        super.doStart();

        // Establish the Server URI
        serverUri = server.getURI().resolve("/");
        wsUri = WSURI.toWebsocket(serverUri);
        super.doStart();
    }

    public URI getServerUri()
    {
        return serverUri;
    }

    public URI getWsUri()
    {
        return wsUri;
    }

    public abstract static class BaseNegotiator implements WebSocketNegotiator
    {
        protected final WebSocketComponents components;

        public BaseNegotiator()
        {
            this.components = new WebSocketComponents();
        }

        @Override
        public void customize(Configuration configurable)
        {
        }

        @Override
        public WebSocketExtensionRegistry getExtensionRegistry()
        {
            return components.getExtensionRegistry();
        }

        @Override
        public DecoratedObjectFactory getObjectFactory()
        {
            return components.getObjectFactory();
        }

        @Override
        public ByteBufferPool getByteBufferPool()
        {
            return components.getBufferPool();
        }

        @Override
        public WebSocketComponents getWebSocketComponents()
        {
            return components;
        }
    }

    public static class EchoNegotiator extends BaseNegotiator
    {
        @Override
        public FrameHandler negotiate(Negotiation negotiation) throws IOException
        {
            List<String> offeredSubProtocols = negotiation.getOfferedSubprotocols();

            if (offeredSubProtocols.isEmpty())
            {
                return new WholeMessageEcho();
            }
            else
            {
                for (String offeredSubProtocol : negotiation.getOfferedSubprotocols())
                {
                    if ("echo-whole".equalsIgnoreCase(offeredSubProtocol))
                    {
                        negotiation.setSubprotocol("echo-whole");
                        return new WholeMessageEcho();
                    }

                    if ("echo-frames".equalsIgnoreCase(offeredSubProtocol))
                    {
                        negotiation.setSubprotocol("echo-frames");
                        return new FrameEcho();
                    }
                }
                // no frame handler available for offered subprotocols
                return null;
            }
        }

        @Override
        public void customize(Configuration configurable)
        {
        }
    }
}
