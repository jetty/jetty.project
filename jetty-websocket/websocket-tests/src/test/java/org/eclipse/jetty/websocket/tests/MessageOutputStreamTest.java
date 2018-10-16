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

package org.eclipse.jetty.websocket.tests;

import org.eclipse.jetty.io.LeakTrackingByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.io.FramePipes;
import org.eclipse.jetty.websocket.common.message.MessageOutputStream;
import org.eclipse.jetty.websocket.common.scopes.SimpleContainerScope;
import org.eclipse.jetty.websocket.common.scopes.WebSocketContainerScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.is;

public class MessageOutputStreamTest
{
    private static final Logger LOG = Log.getLogger(MessageOutputStreamTest.class);

    public LeakTrackingByteBufferPool bufferPool = new LeakTrackingByteBufferPool(new MappedByteBufferPool());
    
    private WebSocketPolicy policy;
    private TrackingEndpoint remoteSocket;
    private WebSocketSession session;
    private WebSocketSession remoteSession;

    @AfterEach
    public void closeSession() throws Exception
    {
        session.close();
        session.stop();
        remoteSession.close();
        remoteSession.stop();
    }

    @BeforeEach
    public void setupSession(TestInfo testInfo) throws Exception
    {
        policy = WebSocketPolicy.newServerPolicy();
        policy.setInputBufferSize(1024);
        
        // Container
        WebSocketContainerScope containerScope = new SimpleContainerScope(policy, bufferPool);
        
        // remote socket
        remoteSocket = new TrackingEndpoint("remote");
        URI remoteURI = new URI("ws://localhost/remote");
        LocalWebSocketConnection remoteConnection = new LocalWebSocketConnection(remoteURI, bufferPool);
        remoteSession = new WebSocketSession(containerScope, remoteURI, remoteSocket, remoteConnection);
        remoteSession.start();
        remoteSession.connect();
        remoteSession.open();
        
        // Local Session
        TrackingEndpoint localSocket = new TrackingEndpoint("local");
        URI localURI = new URI("ws://localhost/local");
        LocalWebSocketConnection localConnection = new LocalWebSocketConnection(localURI, bufferPool);
        session = new WebSocketSession(containerScope, localURI, localSocket, localConnection);
        

        // talk to our remote socket
        session.setOutgoingHandler(FramePipes.to(remoteSession));
        session.connect();
        // start session
        session.start();
        // open connection
        session.open();
    }
    
    @Test //(timeout = 2000)
    public void testMultipleWrites() throws Exception
    {
        try (MessageOutputStream stream = new MessageOutputStream(session))
        {
            stream.write("Hello".getBytes("UTF-8"));
            stream.write(" ".getBytes("UTF-8"));
            stream.write("World".getBytes("UTF-8"));
        }

        assertThat("Socket.messageQueue.size", remoteSocket.bufferQueue.size(), is(1));
        ByteBuffer buffer = remoteSocket.bufferQueue.poll(1, TimeUnit.SECONDS);
        String message = BufferUtil.toUTF8String(buffer);
        assertThat("Message", message, is("Hello World"));

    }
    
    @Test // (timeout = 2000)
    public void testSingleWrite() throws Exception
    {
        try (MessageOutputStream stream = new MessageOutputStream(session))
        {
            stream.write("Hello World".getBytes("UTF-8"));
        }

        assertThat("Socket.messageQueue.size", remoteSocket.bufferQueue.size(), is(1));
        ByteBuffer buffer = remoteSocket.bufferQueue.poll(1, TimeUnit.SECONDS);
        String message = BufferUtil.toUTF8String(buffer);
        assertThat("Message", message, is("Hello World"));
    }
    
    @Test // (timeout = 2000000)
    public void testWriteMultipleBuffers() throws Exception
    {
        int bufsize = (int) (policy.getOutputBufferSize() * 2.5);
        byte buf[] = new byte[bufsize];
        LOG.debug("Buffer sizes: max:{}, test:{}", policy.getOutputBufferSize(), bufsize);
        Arrays.fill(buf, (byte) 'x');
        buf[bufsize - 1] = (byte) 'o'; // mark last entry for debugging
        
        try (MessageOutputStream stream = new MessageOutputStream(session))
        {
            stream.write(buf);
        }

        assertThat("Socket.messageQueue.size", remoteSocket.bufferQueue.size(), is(1));
        ByteBuffer buffer = remoteSocket.bufferQueue.poll(1, TimeUnit.SECONDS);
        String message = BufferUtil.toUTF8String(buffer);
        assertThat("Message", message, endsWith("xxxxxo"));
    }
}
