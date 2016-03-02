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

package org.eclipse.jetty.websocket.common.test;

import static org.hamcrest.Matchers.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.junit.Assert;

/**
 * A overly simplistic websocket server used during testing.
 * <p>
 * This is not meant to be performant or accurate. In fact, having the server misbehave is a useful trait during testing.
 */
public class BlockheadServer
{
    private static final Logger LOG = Log.getLogger(BlockheadServer.class);
    private ServerSocket serverSocket;
    private URI wsUri;

    public IBlockheadServerConnection accept() throws IOException
    {
        LOG.debug(".accept()");
        assertIsStarted();
        Socket socket = serverSocket.accept();
        return new BlockheadServerConnection(socket);
    }

    private void assertIsStarted()
    {
        Assert.assertThat("ServerSocket",serverSocket,notNullValue());
        Assert.assertThat("ServerSocket.isBound",serverSocket.isBound(),is(true));
        Assert.assertThat("ServerSocket.isClosed",serverSocket.isClosed(),is(false));

        Assert.assertThat("WsUri",wsUri,notNullValue());
    }

    public URI getWsUri()
    {
        return wsUri;
    }

    public void start() throws IOException
    {
        InetAddress addr = InetAddress.getByName("localhost");
        serverSocket = new ServerSocket();
        InetSocketAddress endpoint = new InetSocketAddress(addr,0);
        serverSocket.bind(endpoint,1);
        int port = serverSocket.getLocalPort();
        String uri = String.format("ws://%s:%d/",addr.getHostAddress(),port);
        wsUri = URI.create(uri);
        LOG.debug("Server Started on {} -> {}",endpoint,wsUri);
    }

    public void stop()
    {
        LOG.debug("Stopping Server");
        try
        {
            serverSocket.close();
        }
        catch (IOException ignore)
        {
            /* ignore */
        }
    }
}
