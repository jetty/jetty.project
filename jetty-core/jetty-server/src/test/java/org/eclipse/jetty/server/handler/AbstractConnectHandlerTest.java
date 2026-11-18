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

package org.eclipse.jetty.server.handler;

import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.buffer.ArrayByteBufferPool;
import org.junit.jupiter.api.AfterEach;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public abstract class AbstractConnectHandlerTest
{
    protected Server server;
    protected ServerConnector serverConnector;
    protected ArrayByteBufferPool.Tracking proxyBufferPool;
    protected Server proxy;
    protected ServerConnector proxyConnector;
    protected ConnectHandler connectHandler;

    protected void prepareProxy() throws Exception
    {
        proxyBufferPool = new ArrayByteBufferPool.Tracking();
        proxy = new Server(null, null, proxyBufferPool);
        proxyConnector = new ServerConnector(proxy);
        proxy.addConnector(proxyConnector);
        connectHandler = new ConnectHandler();
        proxy.setHandler(connectHandler);
        proxy.start();
    }

    @AfterEach
    public void dispose() throws Exception
    {
        try
        {
            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat("Proxy leaks: " + proxyBufferPool.dumpLeaks(), proxyBufferPool.getLeaks().size(), is(0)));
        }
        finally
        {
            disposeServer();
            disposeProxy();
        }
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
