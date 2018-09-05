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

import static org.hamcrest.Matchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import static org.junit.jupiter.api.Assertions.assertTimeout;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;

import org.eclipse.jetty.io.LeakTrackingByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.io.FramePipes;
import org.eclipse.jetty.websocket.common.message.MessageWriter;
import org.eclipse.jetty.websocket.common.scopes.SimpleContainerScope;
import org.eclipse.jetty.websocket.common.scopes.WebSocketContainerScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class MessageWriterTest
{
    private static final Logger LOG = Log.getLogger(MessageWriterTest.class);

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
    public void setupSession() throws Exception
    {
        policy = WebSocketPolicy.newServerPolicy();
        policy.setInputBufferSize(1024);
        policy.setOutputBufferSize(1024);

        // remote socket
        WebSocketContainerScope remoteContainerScope = new SimpleContainerScope(policy, bufferPool);
        remoteSocket = new TrackingEndpoint("remote");
        URI remoteURI = new URI("ws://localhost/remote");
        LocalWebSocketConnection remoteConnection = new LocalWebSocketConnection(remoteURI, bufferPool);
        remoteSession = new WebSocketSession(remoteContainerScope, remoteURI, remoteSocket, remoteConnection);
        remoteSession.start();
        remoteSession.connect();
        remoteSession.open();

        // Local Session
        WebSocketContainerScope localContainerScope = new SimpleContainerScope(policy, bufferPool);
        TrackingEndpoint localSocket = new TrackingEndpoint("local");
        URI localURI = new URI("ws://localhost/local");
        LocalWebSocketConnection localConnection = new LocalWebSocketConnection(localURI, bufferPool);
        session = new WebSocketSession(localContainerScope, localURI, localSocket, localConnection);
        session.setOutgoingHandler(FramePipes.to(remoteSession));
        session.start();
        session.connect();
        session.open();
    }

    @Test
    public void testMultipleWrites() throws Exception
    {
        assertTimeout( Duration.ofMillis( 2000 ), () -> {
            try (MessageWriter stream = new MessageWriter( session ))
            {
                stream.write( "Hello" );
                stream.write( " " );
                stream.write( "World" );
            }

            assertThat( "Socket.messageQueue.size", remoteSocket.messageQueue.size(), is( 1 ) );
            String msg = remoteSocket.messageQueue.poll();
            assertThat( "Message", msg, is( "Hello World" ) );
        });
    }

    @Test
    public void testSingleWrite() throws Exception
    {
        assertTimeout( Duration.ofMillis( 2000 ), () -> {
            try (MessageWriter stream = new MessageWriter(session))
            {
                stream.append("Hello World");
            }

            assertThat("Socket.messageQueue.size",remoteSocket.messageQueue.size(),is(1));
            String msg = remoteSocket.messageQueue.poll();
            assertThat("Message",msg,is("Hello World"));
        });
    }

    @Test
    public void testWriteMultipleBuffers() throws Exception
    {
        assertTimeout( Duration.ofMillis( 2000 ), () -> {
            int size = (int) ( policy.getOutputBufferSize() * 2.5 );
            char buf[] = new char[size];
            if ( LOG.isDebugEnabled() ) LOG.debug( "Buffer size: {}", size );
            Arrays.fill( buf, 'x' );
            buf[size - 1] = 'o'; // mark last entry for debugging

            try (MessageWriter stream = new MessageWriter( session ))
            {
                stream.write( buf );
            }

            assertThat( "Socket.messageQueue.size", remoteSocket.messageQueue.size(), is( 1 ) );
            String msg = remoteSocket.messageQueue.poll();
            String expected = new String( buf );
            assertThat( "Message", msg, is( expected ) );
        });
    }
}