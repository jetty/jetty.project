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

package org.eclipse.jetty.websocket.examples;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.WebSocket;
import org.eclipse.jetty.websocket.WebSocketClient;
import org.eclipse.jetty.websocket.WebSocketClientFactory;

public class EchoClientSocketExample
{
    /**
     * A simple echo socket, on connect it sends a text message, then when it receives the message it tracks it and then closes the connection.
     * <p>
     * There are some fundamental latches provided that a WebSocketClient can use to wait till a socket is connected or even disconnected.
     */
    public static class EchoClientSocket implements WebSocket, WebSocket.OnTextMessage
    {
        private static final Logger LOG = Log.getLogger(EchoClientSocket.class);

        public CountDownLatch connectLatch = new CountDownLatch(1);
        public CountDownLatch disconnectLatch = new CountDownLatch(1);

        public List<String> textMessages = new ArrayList<String>();
        public List<Throwable> errors = new ArrayList<Throwable>();
        private Connection connection;

        @Override
        public void onClose(int closeCode, String message)
        {
            LOG.info("Closed {} : {}",closeCode,message);
            disconnectLatch.countDown();
        }

        @Override
        public void onMessage(String message)
        {
            LOG.info("on Text : {}",message);
            textMessages.add(message);
            connection.close();
        }

        @Override
        public void onOpen(Connection connection)
        {
            this.connection = connection;
            LOG.info("Connection opened : {}",connection);
            connectLatch.countDown();
            try
            {
                connection.sendMessage("Hello WebSocket World");
            }
            catch (IOException e)
            {
                LOG.warn(e);
            }
        }
    }
    
    private static final Logger LOG = Log.getLogger(EchoClientSocketExample.class);

    public static void main(String[] args)
    {
        QueuedThreadPool threadPool = new QueuedThreadPool();
        // Since this is a standalone client, lets make the threadpool
        // be able to shut itself down at System.exit()
        threadPool.setDaemon(true);

        WebSocketClientFactory factory = new WebSocketClientFactory(threadPool);

        factory.getSslContextFactory().setTrustAll(true);
        
        // disable vulnerable SSL protocols
        factory.getSslContextFactory().addExcludeProtocols("SSL","SSLv2","SSLv2Hello","SSLv3");
        // disable vulnerable SSL cipher suites
        factory.getSslContextFactory().addExcludeCipherSuites(
                "SSL_RSA_WITH_DES_CBC_SHA",
                "SSL_DHE_RSA_WITH_DES_CBC_SHA",
                "SSL_DHE_DSS_WITH_DES_CBC_SHA",
                "SSL_RSA_EXPORT_WITH_RC4_40_MD5",
                "SSL_RSA_EXPORT_WITH_DES40_CBC_SHA",
                "SSL_DHE_RSA_EXPORT_WITH_DES40_CBC_SHA",
                "SSL_DHE_DSS_EXPORT_WITH_DES40_CBC_SHA");

        try
        {
            factory.start();

            WebSocketClient client = factory.newWebSocketClient();
            client.setMaxTextMessageSize(50000);

            // Change to "wss://" for secure (SSL) version.
            URI wsUri = new URI("ws://echo.websocket.org/");

            // Our socket endpoint (the client side)
            EchoClientSocket socket = new EchoClientSocket();

            // Open the connection to the destination, wait 10 seconds for it to succeed (toss exception otherwise).
            client.open(wsUri,socket).get(10,TimeUnit.SECONDS);

            // wait till the socket is disconnected
            socket.disconnectLatch.await(2L,TimeUnit.SECONDS);
        }
        catch (Throwable t)
        {
            LOG.warn(t);
        }
        finally
        {
            try
            {
                factory.stop();
            }
            catch (Exception e)
            {
                LOG.ignore(e);
            }
        }
    }
}
