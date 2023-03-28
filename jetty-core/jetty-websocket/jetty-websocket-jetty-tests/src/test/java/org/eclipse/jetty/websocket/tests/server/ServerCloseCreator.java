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

package org.eclipse.jetty.websocket.tests.server;

import java.util.concurrent.LinkedBlockingQueue;

import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.api.WebSocketContainer;
import org.eclipse.jetty.websocket.server.ServerUpgradeRequest;
import org.eclipse.jetty.websocket.server.ServerUpgradeResponse;
import org.eclipse.jetty.websocket.server.WebSocketCreator;
import org.eclipse.jetty.websocket.tests.EchoSocket;

import static java.util.concurrent.TimeUnit.SECONDS;

public class ServerCloseCreator implements WebSocketCreator
{
    private final LinkedBlockingQueue<AbstractCloseEndpoint> createdSocketQueue = new LinkedBlockingQueue<>();

    @Override
    public Object createWebSocket(ServerUpgradeRequest upgradeRequest, ServerUpgradeResponse upgradeResponse, Callback callback)
    {
        AbstractCloseEndpoint closeSocket = null;

        if (upgradeRequest.hasSubProtocol("fastclose"))
        {
            closeSocket = new FastCloseEndpoint();
            upgradeResponse.setAcceptedSubProtocol("fastclose");
        }
        else if (upgradeRequest.hasSubProtocol("fastfail"))
        {
            closeSocket = new FastFailEndpoint();
            upgradeResponse.setAcceptedSubProtocol("fastfail");
        }
        else if (upgradeRequest.hasSubProtocol("container"))
        {
            Context context = upgradeRequest.getContext();
            WebSocketContainer container =
                (WebSocketContainer)context.getAttribute(WebSocketContainer.class.getName());
            closeSocket = new ContainerEndpoint(container);
            upgradeResponse.setAcceptedSubProtocol("container");
        }
        else if (upgradeRequest.hasSubProtocol("closeInOnClose"))
        {
            closeSocket = new CloseInOnCloseEndpoint();
            upgradeResponse.setAcceptedSubProtocol("closeInOnClose");
        }
        else if (upgradeRequest.hasSubProtocol("closeInOnCloseNewThread"))
        {
            closeSocket = new CloseInOnCloseEndpointNewThread();
            upgradeResponse.setAcceptedSubProtocol("closeInOnCloseNewThread");
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
