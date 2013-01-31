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

package org.eclipse.jetty.websocket;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.dummy.DummyServer;
import org.eclipse.jetty.websocket.dummy.DummyServer.ServerConnection;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

public class TomcatServerQuirksTest
{
    /**
     * Test for when encountering a "Transfer-Encoding: chunked" on a Upgrade Response header.
     * <ul>
     * <li><a href="https://bugs.eclipse.org/bugs/show_bug.cgi?id=393075">Eclipse Jetty Bug #393075</a></li>
     * <li><a href="https://issues.apache.org/bugzilla/show_bug.cgi?id=54067">Apache Tomcat Bug #54067</a></li>
     * </ul>
     * @throws IOException 
     */
    @Test
    public void testTomcat7_0_32_WithTransferEncoding() throws Exception 
    {
        DummyServer server = new DummyServer();
        int bufferSize = 512;
        QueuedThreadPool threadPool = new QueuedThreadPool();
        WebSocketClientFactory factory = new WebSocketClientFactory(threadPool, new ZeroMaskGen(), bufferSize);
        
        try 
        {
            server.start();
            
            // Setup Client Factory
            threadPool.start();
            factory.start();
            
            // Create Client
            WebSocketClient client = new WebSocketClient(factory);

            // Create End User WebSocket Class
            final CountDownLatch openLatch = new CountDownLatch(1);
            final CountDownLatch dataLatch = new CountDownLatch(1);
            WebSocket.OnTextMessage websocket = new WebSocket.OnTextMessage()
            {
                public void onOpen(Connection connection)
                {
                    openLatch.countDown();
                }

                public void onMessage(String data)
                {
                    // System.out.println("data = " + data);
                    dataLatch.countDown();
                }

                public void onClose(int closeCode, String message)
                {
                }
            };
            
            // Open connection
            URI wsURI = server.getWsUri();
            client.open(wsURI, websocket);

            // Accept incoming connection
            ServerConnection socket = server.accept();
            socket.setSoTimeout(2000); // timeout
            
            // Issue upgrade
            Map<String,String> extraResponseHeaders = new HashMap<String, String>();
            extraResponseHeaders.put("Transfer-Encoding", "chunked"); // !! The problem !!
            socket.upgrade(extraResponseHeaders);
            
            // Wait for proper upgrade
            Assert.assertTrue("Timed out waiting for Client side WebSocket open event", openLatch.await(1, TimeUnit.SECONDS));

            // Have server write frame.
            int length = bufferSize / 2;
            ByteBuffer serverFrame = ByteBuffer.allocate(bufferSize);
            serverFrame.put((byte)(0x80 | 0x01)); // FIN + TEXT
            serverFrame.put((byte)0x7E); // No MASK and 2 bytes length
            serverFrame.put((byte)(length >> 8)); // first length byte
            serverFrame.put((byte)(length & 0xFF)); // second length byte
            for (int i = 0; i < length; ++i)
                serverFrame.put((byte)'x');
            serverFrame.flip();
            byte buf[] = serverFrame.array();
            socket.write(buf,0,buf.length);
            socket.flush();

            Assert.assertTrue(dataLatch.await(1000, TimeUnit.SECONDS));
        } 
        finally 
        {
            factory.stop();
            threadPool.stop();
            server.stop();
        }
    }
}
