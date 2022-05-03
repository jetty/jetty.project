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

package org.eclipse.jetty.proxy;

import java.io.IOException;
import java.net.Socket;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterEach;

public abstract class AbstractConnectHandlerTest
{
    protected Server server;
    protected ServerConnector serverConnector;
    protected Server proxy;
    protected ServerConnector proxyConnector;
    protected ConnectHandler connectHandler;

    protected void prepareProxy() throws Exception
    {
        proxy = new Server();
        proxyConnector = new ServerConnector(proxy);
        proxy.addConnector(proxyConnector);
        connectHandler = new ConnectHandler();
        proxy.setHandler(connectHandler);
        proxy.start();
    }

    @AfterEach
    public void dispose() throws Exception
    {
        disposeServer();
        disposeProxy();
    }

    protected void disposeServer() throws Exception
    {
        server.stop();
    }

    protected void disposeProxy() throws Exception
    {
        proxy.stop();
    }

    protected Socket newSocket() throws IOException
    {
        Socket socket = new Socket("localhost", proxyConnector.getLocalPort());
        socket.setSoTimeout(20000);
        return socket;
    }
}
