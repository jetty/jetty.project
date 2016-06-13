//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.proxy;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;

import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.toolchain.test.TestTracker;
import org.junit.After;
import org.junit.Rule;

public abstract class AbstractConnectHandlerTest
{
    @Rule
    public final TestTracker tracker = new TestTracker();
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

    @After
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

    protected HttpTester.Response readResponse(InputStream inputStream) throws IOException
    {
        HttpTester.Input input = HttpTester.from(inputStream);
        return HttpTester.parseResponse(input);
    }

    protected Socket newSocket() throws IOException
    {
        Socket socket = new Socket("localhost", proxyConnector.getLocalPort());
        socket.setSoTimeout(5000);
        return socket;
    }
}
