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

package org.eclipse.jetty.websocket.tests.server;

import java.util.concurrent.LinkedBlockingQueue;
import javax.servlet.ServletContext;

import org.eclipse.jetty.websocket.common.WebSocketContainer;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.eclipse.jetty.websocket.tests.EchoSocket;

import static java.util.concurrent.TimeUnit.SECONDS;

public class ServerCloseCreator implements WebSocketCreator
{
    private final WebSocketServletFactory serverFactory;
    private LinkedBlockingQueue<AbstractCloseEndpoint> createdSocketQueue = new LinkedBlockingQueue<>();

    public ServerCloseCreator(WebSocketServletFactory serverFactory)
    {
        this.serverFactory = serverFactory;
    }

    @Override
    public Object createWebSocket(ServletUpgradeRequest req, ServletUpgradeResponse resp)
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
                    (WebSocketContainer) context.getAttribute(WebSocketContainer.class.getName());
            closeSocket = new ContainerEndpoint(container);
            resp.setAcceptedSubProtocol("container");
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
