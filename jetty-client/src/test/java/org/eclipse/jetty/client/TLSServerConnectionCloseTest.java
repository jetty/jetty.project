//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.TestTracker;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.After;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.Collection;

@RunWith(Parameterized.class)
public class TLSServerConnectionCloseTest
{
    @Parameterized.Parameters(name = "CloseMode: {0}")
    public static Collection<Object[]> parameters()
    {
        return Arrays.asList(new Object[]{CloseMode.NONE}, new Object[]{CloseMode.CLOSE}, new Object[]{CloseMode.ABRUPT});
    }

    @Rule
    public final TestTracker tracker = new TestTracker();
    private HttpClient client;
    private final CloseMode closeMode;

    public TLSServerConnectionCloseTest(CloseMode closeMode)
    {
        this.closeMode = closeMode;
    }

    private void startClient() throws Exception
    {
        SslContextFactory sslContextFactory = new SslContextFactory();
        File keystore = MavenTestingUtils.getTestResourceFile("keystore");
        sslContextFactory.setKeyStorePath(keystore.getAbsolutePath());
        sslContextFactory.setKeyStorePassword("storepwd");
        sslContextFactory.setKeyManagerPassword("keypwd");

        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        client = new HttpClient(sslContextFactory);
        client.setThreadPool(clientThreads);
        client.start();
    }

    @After
    public void dispose() throws Exception
    {
        if (client != null)
            client.stop();
    }

    @Test
    public void testServerSendsConnectionCloseWithoutContent() throws Exception
    {
        testServerSendsConnectionClose(false, "");
    }

    @Test
    public void testServerSendsConnectionCloseWithContent() throws Exception
    {
        testServerSendsConnectionClose(false, "data");
    }

    @Test
    public void testServerSendsConnectionCloseWithChunkedContent() throws Exception
    {
        testServerSendsConnectionClose(true, "data");
    }

    private void testServerSendsConnectionClose(boolean chunked, String content) throws Exception
    {
        ServerSocket server = new ServerSocket(0);
        int port = server.getLocalPort();

        startClient();

        ContentExchange exchange = new ContentExchange(true);
        exchange.setURL("https://localhost:" + port + "/ctx/path");
        client.send(exchange);

        Socket socket = server.accept();
        SSLContext sslContext = client.getSslContextFactory().getSslContext();
        SSLSocket sslSocket = (SSLSocket)sslContext.getSocketFactory().createSocket(socket, "localhost", port, false);
        sslSocket.setUseClientMode(false);
        sslSocket.startHandshake();

        InputStream input = sslSocket.getInputStream();
        consumeRequest(input);

        OutputStream output = sslSocket.getOutputStream();
        String serverResponse = "" +
                "HTTP/1.1 200 OK\r\n" +
                "Connection: close\r\n";
        if (chunked)
        {
            serverResponse += "" +
                    "Transfer-Encoding: chunked\r\n" +
                    "\r\n";
                    for (int i = 0; i < 2; ++i)
                    {
                        serverResponse +=
                                Integer.toHexString(content.length()) + "\r\n" +
                                content + "\r\n";
                    }
            serverResponse += "" +
                    "0\r\n" +
                    "\r\n";
        }
        else
        {
            serverResponse += "Content-Length: " + content.length() + "\r\n";
            serverResponse += "\r\n";
            serverResponse += content;
        }

        output.write(serverResponse.getBytes("UTF-8"));
        output.flush();

        switch (closeMode)
        {
            case NONE:
            {
                break;
            }
            case CLOSE:
            {
                sslSocket.close();
                break;
            }
            case ABRUPT:
            {
                socket.shutdownOutput();
                break;
            }
            default:
            {
                throw new IllegalStateException();
            }
        }

        Assert.assertEquals(HttpExchange.STATUS_COMPLETED, exchange.waitForDone());
        Assert.assertEquals(HttpStatus.OK_200, exchange.getResponseStatus());

        // Give some time to process the connection.
        Thread.sleep(1000);

        // Connection should have been removed from pool.
        HttpDestination destination = client.getDestination(new Address("localhost", port), false);
        Assert.assertEquals(0, destination.getConnections());
        Assert.assertEquals(0, destination.getIdleConnections());
    }

    private boolean consumeRequest(InputStream input) throws IOException
    {
        int crlfs = 0;
        while (true)
        {
            int read = input.read();
            if (read < 0)
                return true;
            if (read == '\r' || read == '\n')
                ++crlfs;
            else
                crlfs = 0;
            if (crlfs == 4)
                return false;
        }
    }

    private enum CloseMode
    {
        NONE, CLOSE, ABRUPT
    }
}
