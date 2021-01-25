//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.websocket.common.message;

import java.util.Arrays;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.extensions.OutgoingFrames;
import org.eclipse.jetty.websocket.common.events.EventDriver;
import org.eclipse.jetty.websocket.common.events.EventDriverFactory;
import org.eclipse.jetty.websocket.common.io.FramePipes;
import org.eclipse.jetty.websocket.common.io.LocalWebSocketSession;
import org.eclipse.jetty.websocket.common.scopes.SimpleContainerScope;
import org.eclipse.jetty.websocket.common.scopes.WebSocketContainerScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

public class MessageOutputStreamTest
{
    private static final Logger LOG = Log.getLogger(MessageOutputStreamTest.class);

    public ByteBufferPool bufferPool = new MappedByteBufferPool();

    private WebSocketPolicy policy;
    private TrackingSocket socket;
    private LocalWebSocketSession session;

    @AfterEach
    public void closeSession() throws Exception
    {
        session.close();
        session.stop();
    }

    @BeforeEach
    public void setupSession(TestInfo testInfo) throws Exception
    {
        policy = WebSocketPolicy.newServerPolicy();
        policy.setInputBufferSize(1024);
        policy.setMaxBinaryMessageBufferSize(1024);

        // Container
        WebSocketContainerScope containerScope = new SimpleContainerScope(policy, bufferPool);

        // Event Driver factory
        EventDriverFactory factory = new EventDriverFactory(containerScope);

        // local socket
        EventDriver driver = factory.wrap(new TrackingSocket("local"));

        // remote socket
        socket = new TrackingSocket("remote");
        OutgoingFrames socketPipe = FramePipes.to(factory.wrap(socket));

        String id = testInfo.getDisplayName();
        session = new LocalWebSocketSession(containerScope, id, driver);

        session.setPolicy(policy);
        // talk to our remote socket
        session.setOutgoingHandler(socketPipe);
        // start session
        session.start();
        // open connection
        session.open();
    }

    @Test
    public void testMultipleWrites() throws Exception
    {
        try (MessageOutputStream stream = new MessageOutputStream(session))
        {
            stream.write("Hello".getBytes("UTF-8"));
            stream.write(" ".getBytes("UTF-8"));
            stream.write("World".getBytes("UTF-8"));
        }

        assertThat("Socket.messageQueue.size", socket.messageQueue.size(), is(1));
        String msg = socket.messageQueue.poll();
        assertThat("Message", msg, allOf(containsString("byte[11]"), containsString("Hello World")));
    }

    @Test
    public void testSingleWrite() throws Exception
    {
        try (MessageOutputStream stream = new MessageOutputStream(session))
        {
            stream.write("Hello World".getBytes("UTF-8"));
        }

        assertThat("Socket.messageQueue.size", socket.messageQueue.size(), is(1));
        String msg = socket.messageQueue.poll();
        assertThat("Message", msg, allOf(containsString("byte[11]"), containsString("Hello World")));
    }

    @Test
    public void testWriteMultipleBuffers() throws Exception
    {
        int bufsize = (int)(policy.getMaxBinaryMessageBufferSize() * 2.5);
        byte[] buf = new byte[bufsize];
        LOG.debug("Buffer sizes: max:{}, test:{}", policy.getMaxBinaryMessageBufferSize(), bufsize);
        Arrays.fill(buf, (byte)'x');
        buf[bufsize - 1] = (byte)'o'; // mark last entry for debugging

        try (MessageOutputStream stream = new MessageOutputStream(session))
        {
            stream.write(buf);
        }

        assertThat("Socket.messageQueue.size", socket.messageQueue.size(), is(1));
        String msg = socket.messageQueue.poll();
        assertThat("Message", msg, allOf(containsString("byte[" + bufsize + "]"), containsString("xxxo>>>")));
    }
}
