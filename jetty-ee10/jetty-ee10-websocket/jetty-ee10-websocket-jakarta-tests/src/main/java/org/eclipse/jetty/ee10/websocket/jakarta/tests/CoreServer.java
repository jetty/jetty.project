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

package org.eclipse.jetty.ee10.websocket.jakarta.tests;

import java.net.URI;
import java.util.List;

import org.eclipse.jetty.ee10.websocket.jakarta.tests.framehandlers.FrameEcho;
import org.eclipse.jetty.ee10.websocket.jakarta.tests.framehandlers.WholeMessageEcho;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.server.ServerUpgradeRequest;
import org.eclipse.jetty.websocket.core.server.ServerUpgradeResponse;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiator;
import org.eclipse.jetty.websocket.core.server.WebSocketUpgradeHandler;

public class CoreServer extends ContainerLifeCycle
{
    private Server server;
    private ServerConnector connector;
    private WebSocketNegotiator negotiator;
    private URI serverUri;
    private URI wsUri;

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
        WebSocketUpgradeHandler upgradeHandler = new WebSocketUpgradeHandler();
        upgradeHandler.addMapping("/*", negotiator);
        server.setHandler(upgradeHandler);

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

    public static class EchoNegotiator extends WebSocketNegotiator.AbstractNegotiator
    {
        @Override
        public FrameHandler negotiate(ServerUpgradeRequest request, ServerUpgradeResponse response, Callback callback)
        {
            List<String> offeredSubProtocols = request.getSubProtocols();
            if (offeredSubProtocols.isEmpty())
            {
                return new WholeMessageEcho();
            }
            else
            {
                for (String offeredSubProtocol : offeredSubProtocols)
                {
                    if ("echo-whole".equalsIgnoreCase(offeredSubProtocol))
                    {
                        response.setAcceptedSubProtocol("echo-whole");
                        return new WholeMessageEcho();
                    }

                    if ("echo-frames".equalsIgnoreCase(offeredSubProtocol))
                    {
                        response.setAcceptedSubProtocol("echo-frames");
                        return new FrameEcho();
                    }
                }
                // no frame handler available for offered subprotocols
                return null;
            }
        }
    }
}
