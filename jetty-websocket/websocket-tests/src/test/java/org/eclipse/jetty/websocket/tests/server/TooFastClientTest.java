//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.tests.Defaults;
import org.eclipse.jetty.websocket.tests.LeakTrackingBufferPoolRule;
import org.eclipse.jetty.websocket.tests.SimpleServletServer;
import org.eclipse.jetty.websocket.tests.TrackingEndpoint;
import org.eclipse.jetty.websocket.tests.servlets.EchoServlet;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

/**
 * Test simulating a client that talks too quickly.
 * <p>
 * There is a class of client that will send the GET+Upgrade Request along with a few websocket frames in a single
 * network packet. This test attempts to perform this behavior as close as possible.
 */
public class TooFastClientTest
{
    private static SimpleServletServer server;
    
    @BeforeClass
    public static void startServer() throws Exception
    {
        server = new SimpleServletServer(new EchoServlet());
        server.start();
    }
    
    @AfterClass
    public static void stopServer() throws Exception
    {
        server.stop();
    }
    
    @Rule
    LeakTrackingBufferPoolRule bufferPool = new LeakTrackingBufferPoolRule(TooFastClientTest.class.getSimpleName());
    
    @Rule
    public TestName testname = new TestName();
    
    private WebSocketClient client;
    
    @Before
    public void startClient() throws Exception
    {
        client = new WebSocketClient();
        client.start();
    }
    
    @After
    public void stopClient() throws Exception
    {
        client.stop();
    }
    
    @Test
    public void testUpgradeWithSmallFrames() throws Exception
    {
        URI wsUri = server.getServerUri();
        
        TrackingEndpoint clientSocket = new TrackingEndpoint(testname.getMethodName());
        ClientUpgradeRequest upgradeRequest = new ClientUpgradeRequest();
        
        /* TODO
         Generate the Request ByteBuffer.
         Complete with ..
           * A WebSocket Upgrade Request URI
           * A WebSocket Upgrade Request Headers
           * A few outgoing WebSocket frames
         Send this ByteBuffer as the complete HTTP request bytebuffer.
        
        // Create ByteBuffer representing the initial opening network packet from the client
        ByteBuffer initialPacket = ByteBuffer.allocate(4096);
        BufferUtil.clearToFill(initialPacket);
        
        // Add upgrade request to packet
        StringBuilder upgradeRequest = client.generateUpgradeRequest();
        ByteBuffer upgradeBuffer = BufferUtil.toBuffer(upgradeRequest.toString(), StandardCharsets.UTF_8);
        initialPacket.put(upgradeBuffer);
        
        // Add text frames
        Generator generator = new Generator(WebSocketPolicy.newClientPolicy(), bufferPool);
        
        String msg1 = "Echo 1";
        String msg2 = "This is also an echooooo!";
        
        TextFrame frame1 = new TextFrame().setPayload(msg1);
        TextFrame frame2 = new TextFrame().setPayload(msg2);
        
        // Need to set frame mask (as these are client frames)
        byte mask[] = new byte[]{0x11, 0x22, 0x33, 0x44};
        frame1.setMask(mask);
        frame2.setMask(mask);
        
        generator.generateWholeFrame(frame1, initialPacket);
        generator.generateWholeFrame(frame2, initialPacket);
        
        // Write packet to network
        BufferUtil.flipToFlush(initialPacket, 0);
        client.writeRaw(initialPacket);
         */
        
        Future<Session> clientConnectFuture = client.connect(clientSocket, wsUri, upgradeRequest);
        
        // Expect upgrade success
        Session clientSession = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        
        // Read incoming messages
        String incomingMessage;
        incomingMessage = clientSocket.messageQueue.poll(5, TimeUnit.SECONDS);
        assertThat("Echoed Incoming Message 1", incomingMessage, is("Echo 1"));
        incomingMessage = clientSocket.messageQueue.poll(5, TimeUnit.SECONDS);
        assertThat("Echoed Incoming Message 2", incomingMessage, is("This is also an echooooo!"));
        
        clientSession.close();
    }
    
    /**
     * Test where were a client sends a HTTP Upgrade to websocket AND enough websocket frame(s)
     * to completely overfill the {@link org.eclipse.jetty.io.AbstractConnection#getInputBufferSize()}
     * to test a situation where the WebSocket connection opens with prefill that exceeds
     * the normal input buffer sizes.
     *
     * @throws Exception on test failure
     */
    @Test
    public void testUpgradeWithLargeFrame() throws Exception
    {
        URI wsUri = server.getServerUri();
        
        TrackingEndpoint clientSocket = new TrackingEndpoint(testname.getMethodName());
        ClientUpgradeRequest upgradeRequest = new ClientUpgradeRequest();
    
        byte bigMsgBytes[] = new byte[64 * 1024];
        Arrays.fill(bigMsgBytes, (byte) 'x');
        String bigMsg = new String(bigMsgBytes, StandardCharsets.UTF_8);
        
        /* TODO
         Generate the Request ByteBuffer.
         Complete with ..
           * A WebSocket Upgrade Request URI
           * A WebSocket Upgrade Request Headers
           * A big enough outgoing WebSocket frame
             that will trigger a prefill + an unread buffer
         Send this ByteBuffer as the complete HTTP request bytebuffer.
        
        // Create ByteBuffer representing the initial opening network packet from the client
        ByteBuffer initialPacket = ByteBuffer.allocate(100 * 1024);
        BufferUtil.clearToFill(initialPacket);
        
        // Add upgrade request to packet
        StringBuilder upgradeRequest = client.generateUpgradeRequest();
        ByteBuffer upgradeBuffer = BufferUtil.toBuffer(upgradeRequest.toString(), StandardCharsets.UTF_8);
        initialPacket.put(upgradeBuffer);
        
        // Add text frames
        Generator generator = new Generator(WebSocketPolicy.newClientPolicy(), bufferPool);
        
        // Need to set frame mask (as these are client frames)
        byte mask[] = new byte[]{0x11, 0x22, 0x33, 0x44};
        TextFrame frame = new TextFrame().setPayload(bigMsg);
        frame.setMask(mask);
        generator.generateWholeFrame(frame, initialPacket);
        
        // Write packet to network
        BufferUtil.flipToFlush(initialPacket, 0);
        client.writeRaw(initialPacket);
        
        // Expect upgrade
        client.expectUpgradeResponse();
        */
        
        Future<Session> clientConnectFuture = client.connect(clientSocket, wsUri, upgradeRequest);
    
        // Expect upgrade success
        Session clientSession = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    
        // Read incoming messages
        String incomingMessage;
        incomingMessage = clientSocket.messageQueue.poll(5, TimeUnit.SECONDS);
        assertThat("Echoed Incoming Message 1", incomingMessage, is(bigMsg));
        
        clientSession.close();
    }
    
    
}
