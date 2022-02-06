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

package org.eclipse.jetty.websocket.tests.server;

import java.util.concurrent.LinkedBlockingQueue;
import javax.servlet.ServletContext;

import org.eclipse.jetty.websocket.api.WebSocketContainer;
import org.eclipse.jetty.websocket.server.JettyServerUpgradeRequest;
import org.eclipse.jetty.websocket.server.JettyServerUpgradeResponse;
import org.eclipse.jetty.websocket.server.JettyWebSocketCreator;
import org.eclipse.jetty.websocket.server.JettyWebSocketServletFactory;
import org.eclipse.jetty.websocket.tests.EchoSocket;

import static java.util.concurrent.TimeUnit.SECONDS;

public class ServerCloseCreator implements JettyWebSocketCreator
{
    private final JettyWebSocketServletFactory serverFactory;
    private LinkedBlockingQueue<AbstractCloseEndpoint> createdSocketQueue = new LinkedBlockingQueue<>();

    public ServerCloseCreator(JettyWebSocketServletFactory serverFactory)
    {
        this.serverFactory = serverFactory;
    }

    @Override
    public Object createWebSocket(JettyServerUpgradeRequest req, JettyServerUpgradeResponse resp)
    {
        AbstractCloseEndpoint closeSocket = null;

        if (req.hasSubProtocol("fastclose"))
        {
            closeSocket = new FastCloseEndpoint();
            resp.setAcceptedSubProtocol("fastclose");
        }
        else if (req.hasSubProtocol("fastfail"))
        {
            closeSocket = new FastFailEndpoint();
            resp.setAcceptedSubProtocol("fastfail");
        }
        else if (req.hasSubProtocol("container"))
        {
            ServletContext context = req.getHttpServletRequest().getServletContext();
            WebSocketContainer container =
                (WebSocketContainer)context.getAttribute(WebSocketContainer.class.getName());
            closeSocket = new ContainerEndpoint(container);
            resp.setAcceptedSubProtocol("container");
        }
        else if (req.hasSubProtocol("closeInOnClose"))
        {
            closeSocket = new CloseInOnCloseEndpoint();
            resp.setAcceptedSubProtocol("closeInOnClose");
        }
        else if (req.hasSubProtocol("closeInOnCloseNewThread"))
        {
            closeSocket = new CloseInOnCloseEndpointNewThread();
            resp.setAcceptedSubProtocol("closeInOnCloseNewThread");
        }

        if (closeSocket != null)
        {
            createdSocketQueue.offer(closeSocket);
            return closeSocket;
        }
        else
        {
            return new EchoSocket();
        }
    }

    public AbstractCloseEndpoint pollLastCreated() throws InterruptedException
    {
        return createdSocketQueue.poll(5, SECONDS);
    }
}
