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

package org.eclipse.jetty.websocket.javax.tests;

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
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.WebSocketExtensionRegistry;
import org.eclipse.jetty.websocket.core.server.Negotiation;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiator;
import org.eclipse.jetty.websocket.core.server.WebSocketUpgradeHandler;
import org.eclipse.jetty.websocket.javax.tests.framehandlers.FrameEcho;
import org.eclipse.jetty.websocket.javax.tests.framehandlers.WholeMessageEcho;

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
        public void customize(FrameHandler.Configuration configurable)
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
        public void customize(FrameHandler.Configuration configurable)
        {
        }
    }
}
