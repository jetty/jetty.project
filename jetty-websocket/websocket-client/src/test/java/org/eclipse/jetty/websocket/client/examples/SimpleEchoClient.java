//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.client.examples;

import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketConnection;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;

/**
 * Example of a simple Echo Client.
 */
public class SimpleEchoClient
{
    @WebSocket
    public static class SimpleEchoSocket
    {
        private final CountDownLatch closeLatch;
        @SuppressWarnings("unused")
        private WebSocketConnection conn;

        public SimpleEchoSocket()
        {
            this.closeLatch = new CountDownLatch(1);
        }

        public boolean awaitClose(int duration, TimeUnit unit) throws InterruptedException
        {
            return this.closeLatch.await(duration,unit);
        }

        @OnWebSocketClose
        public void onClose(int statusCode, String reason)
        {
            System.out.printf("Connection closed: %d - %s%n",statusCode,reason);
            this.conn = null;
            this.closeLatch.countDown(); // trigger latch
        }

        @OnWebSocketConnect
        public void onConnect(WebSocketConnection conn)
        {
            System.out.printf("Got connect: %s%n",conn);
            this.conn = conn;
            try
            {
                Future<Void> fut;
                fut = conn.write("Hello");
                fut.get(2,TimeUnit.SECONDS); // wait for send to complete.

                fut = conn.write("Thanks for the conversation.");
                fut.get(2,TimeUnit.SECONDS); // wait for send to complete.

                conn.close(StatusCode.NORMAL,"I'm done");
            }
            catch (Throwable t)
            {
                t.printStackTrace();
            }
        }

        @OnWebSocketMessage
        public void onMessage(String msg)
        {
            System.out.printf("Got msg: %s%n",msg);
        }
    }

    public static void main(String[] args)
    {
        String destUri = "ws://echo.websocket.org";
        if (args.length > 0)
        {
            destUri = args[0];
        }

        WebSocketClient client = new WebSocketClient();
        SimpleEchoSocket socket = new SimpleEchoSocket();
        try
        {
            client.start();

            URI echoUri = new URI(destUri);
            ClientUpgradeRequest request = new ClientUpgradeRequest();
            request.addExtensions("x-webkit-deflate-frame");
            client.connect(socket,echoUri,request);
            System.out.printf("Connecting to : %s%n",echoUri);

            // wait for closed socket connection.
            socket.awaitClose(5,TimeUnit.SECONDS);
        }
        catch (Throwable t)
        {
            t.printStackTrace();
        }
        finally
        {
            try
            {
                client.stop();
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }
    }
}
