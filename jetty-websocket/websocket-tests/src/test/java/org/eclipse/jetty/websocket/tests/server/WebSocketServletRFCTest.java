//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.tests.server;

import static org.hamcrest.Matchers.anything;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.toolchain.test.Hex;
import org.eclipse.jetty.util.Utf8Appendable.NotUtf8Exception;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.util.log.StdErrLog;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.common.Generator;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.frames.BinaryFrame;
import org.eclipse.jetty.websocket.common.frames.ContinuationFrame;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.eclipse.jetty.websocket.tests.Defaults;
import org.eclipse.jetty.websocket.tests.SimpleServletServer;
import org.eclipse.jetty.websocket.tests.UntrustedWSClient;
import org.eclipse.jetty.websocket.tests.UntrustedWSConnection;
import org.eclipse.jetty.websocket.tests.UntrustedWSEndpoint;
import org.eclipse.jetty.websocket.tests.UntrustedWSSession;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

/**
 * Test various <a href="http://tools.ietf.org/html/rfc6455">RFC 6455</a> specified requirements placed on {@link WebSocketServlet}
 */
public class WebSocketServletRFCTest
{
    private static SimpleServletServer server;
    
    @BeforeClass
    public static void startServer() throws Exception
    {
        server = new SimpleServletServer(new WebSocketServlet()
        {
            @Override
            public void configure(WebSocketServletFactory factory)
            {
                factory.register(RFC6455Socket.class);
            }
        });
        server.start();
    }
    
    @AfterClass
    public static void stopServer() throws Exception
    {
        server.stop();
    }
    
    @Rule
    public TestName testname = new TestName();
    
    private UntrustedWSClient client;
    
    @Before
    public void startClient() throws Exception
    {
        client = new UntrustedWSClient();
        client.start();
    }
    
    @After
    public void stopClient() throws Exception
    {
        client.stop();
    }
    
    /**
     * @param clazz the class to enable
     * @param enabled true to enable the stack traces (or not)
     * @deprecated use {@link StacklessLogging} in a try-with-resources block instead
     */
    @Deprecated
    private void enableStacks(Class<?> clazz, boolean enabled)
    {
        StdErrLog log = StdErrLog.getLogger(clazz);
        log.setHideStacks(!enabled);
    }
    
    /**
     * Test that aggregation of binary frames into a single message occurs
     *
     * @throws Exception on test failure
     */
    @Test
    public void testBinaryAggregate() throws Exception
    {
        URI wsUri = server.getServerUri();
        
        ClientUpgradeRequest req = new ClientUpgradeRequest();
        Future<UntrustedWSSession> clientConnectFuture = client.connect(wsUri, req);
        
        UntrustedWSSession clientSession = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        UntrustedWSConnection clientConnection = clientSession.getUntrustedConnection();
        
        // Generate binary frames
        byte buf1[] = new byte[128];
        byte buf2[] = new byte[128];
        byte buf3[] = new byte[128];
        
        Arrays.fill(buf1, (byte) 0xAA);
        Arrays.fill(buf2, (byte) 0xBB);
        Arrays.fill(buf3, (byte) 0xCC);
        
        WebSocketFrame bin;
        
        bin = new BinaryFrame().setPayload(buf1).setFin(false);
        
        clientConnection.write(bin); // write buf1 (fin=false)
        
        bin = new ContinuationFrame().setPayload(buf2).setFin(false);
        
        clientConnection.write(bin); // write buf2 (fin=false)
        
        bin = new ContinuationFrame().setPayload(buf3).setFin(true);
        
        clientConnection.write(bin); // write buf3 (fin=true)
        
        // Read frame echo'd back (hopefully a single binary frame)
        WebSocketFrame incomingFrame = clientSession.getUntrustedEndpoint().framesQueue.poll(5, TimeUnit.SECONDS);
        
        int expectedSize = buf1.length + buf2.length + buf3.length;
        assertThat("BinaryFrame.payloadLength", incomingFrame.getPayloadLength(), is(expectedSize));
        
        int aaCount = 0;
        int bbCount = 0;
        int ccCount = 0;
        
        ByteBuffer echod = incomingFrame.getPayload();
        while (echod.remaining() >= 1)
        {
            byte b = echod.get();
            switch (b)
            {
                case (byte) 0xAA:
                    aaCount++;
                    break;
                case (byte) 0xBB:
                    bbCount++;
                    break;
                case (byte) 0xCC:
                    ccCount++;
                    break;
                default:
                    Assert.fail(String.format("Encountered invalid byte 0x%02X", (byte) (0xFF & b)));
            }
        }
        
        assertThat("Echoed data count for 0xAA", aaCount, is(buf1.length));
        assertThat("Echoed data count for 0xBB", bbCount, is(buf2.length));
        assertThat("Echoed data count for 0xCC", ccCount, is(buf3.length));
        
        clientSession.close();
    }
    
    @Test(expected = NotUtf8Exception.class)
    public void testDetectBadUTF8()
    {
        byte buf[] = new byte[]
                {(byte) 0xC2, (byte) 0xC3};
        
        Utf8StringBuilder utf = new Utf8StringBuilder();
        utf.append(buf, 0, buf.length);
    }
    
    /**
     * Test the requirement of responding with server terminated close code 1011 when there is an unhandled (internal server error) being produced by the
     * WebSocket POJO.
     *
     * @throws Exception on test failure
     */
    @Test
    public void testInternalError() throws Exception
    {
        URI wsUri = server.getServerUri();
        
        ClientUpgradeRequest req = new ClientUpgradeRequest();
        Future<UntrustedWSSession> clientConnectFuture = client.connect(wsUri, req);
        
        UntrustedWSSession clientSession = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        UntrustedWSEndpoint clientSocket = clientSession.getUntrustedEndpoint();

        try (StacklessLogging ignored = new StacklessLogging(
                Log.getLogger(WebSocketSession.class.getName() + ".SERVER"),
                Log.getLogger(RFC6455Socket.class)))
        {
            clientSession.getRemote().sendString("CRASH");

            clientSocket.awaitCloseEvent("Client");
            clientSocket.assertCloseInfo("Client", StatusCode.SERVER_ERROR, anything());
        }
    }
    
    /**
     * Test http://tools.ietf.org/html/rfc6455#section-4.1 where server side upgrade handling is supposed to be case insensitive.
     * <p>
     * This test will simulate a client requesting upgrade with all lowercase headers.
     *
     * @throws Exception on test failure
     */
    @Test
    public void testLowercaseUpgrade() throws Exception
    {
        URI wsUri = server.getServerUri();
    
        ClientUpgradeRequest req = new ClientUpgradeRequest();
        req.setHeader("upgrade", "websocket");
        req.setHeader("connection", "upgrade");
        req.setHeader("sec-websocket-key", UntrustedWSClient.getStaticWebSocketKey());
        req.setHeader("sec-websocket-origin", wsUri.toASCIIString());
        req.setHeader("sec-websocket-protocol", "echo");
        req.setHeader("sec-websocket-version", "13");
        Future<UntrustedWSSession> clientConnectFuture = client.connect(wsUri, req);
    
        UntrustedWSSession clientSession = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        UntrustedWSEndpoint clientSocket = clientSession.getUntrustedEndpoint();
    
        // Generate text frame
        String msg = "this is an echo ... cho ... ho ... o";
        clientSocket.getRemote().sendString(msg);
    
        // Read frame (hopefully text frame)
        String incomingMessage = clientSocket.messageQueue.poll(5, TimeUnit.SECONDS);
        assertThat("Incoming Message", incomingMessage, is(msg));
    
        clientSession.close();
    
    }
    
    
    /**
     * Test http://tools.ietf.org/html/rfc6455#section-4.1 where server side upgrade handling is supposed to be case insensitive.
     * <p>
     * This test will simulate a client requesting upgrade with all uppercase headers.
     *
     * @throws Exception on test failure
     */
    @Test
    public void testUppercaseUpgrade() throws Exception
    {
        URI wsUri = server.getServerUri();
    
        ClientUpgradeRequest req = new ClientUpgradeRequest();
        req.setHeader("UPGRADE", "WEBSOCKET");
        req.setHeader("CONNECTION", "UPGRADE");
        req.setHeader("SEC-WEBSOCKET-KEY", UntrustedWSClient.getStaticWebSocketKey());
        req.setHeader("SEC-WEBSOCKET-ORIGIN", wsUri.toASCIIString());
        req.setHeader("SEC-WEBSOCKET-PROTOCOL", "ECHO");
        req.setHeader("SEC-WEBSOCKET-VERSION", "13");
        Future<UntrustedWSSession> clientConnectFuture = client.connect(wsUri, req);
    
        UntrustedWSSession clientSession = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        UntrustedWSEndpoint clientSocket = clientSession.getUntrustedEndpoint();
        
        // Generate text frame
        String msg = "this is an echo ... cho ... ho ... o";
        clientSocket.getRemote().sendString(msg);
        
        // Read frame (hopefully text frame)
        String incomingMessage = clientSocket.messageQueue.poll(5, TimeUnit.SECONDS);
        assertThat("Incoming Message", incomingMessage, is(msg));
        
        clientSession.close();
    }
    
    @Test
    public void testTextNotUTF8() throws Exception
    {
        URI wsUri = server.getServerUri();
        
        ClientUpgradeRequest req = new ClientUpgradeRequest();
        req.setSubProtocols("other");
        Future<UntrustedWSSession> clientConnectFuture = client.connect(wsUri, req);
        
        UntrustedWSSession clientSession = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        UntrustedWSConnection clientConnection = clientSession.getUntrustedConnection();
        UntrustedWSEndpoint clientSocket = clientSession.getUntrustedEndpoint();
        
        byte buf[] = new byte[]{(byte) 0xC2, (byte) 0xC3};
        
        Generator generator = new Generator(WebSocketPolicy.newServerPolicy(), client.getBufferPool(), false);

        try (StacklessLogging ignored = new StacklessLogging(RFC6455Socket.class))
        {
            WebSocketFrame txt = new TextFrame().setPayload(ByteBuffer.wrap(buf));
            txt.setMask(Hex.asByteArray("11223344"));
            ByteBuffer bbHeader = generator.generateHeaderBytes(txt);

            clientConnection.writeRaw(bbHeader);
            clientConnection.writeRaw(txt.getPayload());

            clientSocket.awaitCloseEvent("Client");
            clientSocket.assertCloseInfo("Client", StatusCode.BAD_PAYLOAD, anything());
        }
    }
}
