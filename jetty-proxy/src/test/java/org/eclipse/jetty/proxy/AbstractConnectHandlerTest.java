//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import java.io.BufferedReader;
import java.io.IOException;
import java.net.Socket;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.toolchain.test.http.SimpleHttpParser;
import org.eclipse.jetty.toolchain.test.http.SimpleHttpResponse;
import org.junit.After;

public abstract class AbstractConnectHandlerTest
{
    protected Server server;
    protected ServerConnector serverConnector;
    protected Server proxy;
    protected Connector proxyConnector;
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

    protected SimpleHttpResponse readResponse(BufferedReader reader) throws IOException
    {
        return new SimpleHttpParser().readResponse(reader);
    }

    protected Socket newSocket() throws IOException
    {
        Socket socket = new Socket("localhost", ((NetworkConnector)proxyConnector).getLocalPort());
        socket.setSoTimeout(5000);
        return socket;
    }
}
