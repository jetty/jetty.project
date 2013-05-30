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

package org.eclipse.jetty.websocket.common.message;

import static org.hamcrest.Matchers.*;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.eclipse.jetty.toolchain.test.TestTracker;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.extensions.OutgoingFrames;
import org.eclipse.jetty.websocket.common.events.EventDriver;
import org.eclipse.jetty.websocket.common.events.EventDriverFactory;
import org.eclipse.jetty.websocket.common.io.FramePipes;
import org.eclipse.jetty.websocket.common.io.LocalWebSocketSession;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

public class MessageInputStreamTest
{
    @Rule
    public TestTracker testtracker = new TestTracker();

    @Rule
    public TestName testname = new TestName();

    private WebSocketPolicy policy;
    private TrackingInputStreamSocket socket;
    private LocalWebSocketSession session;
    private LocalWebSocketSession remoteSession;

    @After
    public void closeSession()
    {
        session.close();
        remoteSession.close();
    }

    @Before
    public void setupSession()
    {
        policy = WebSocketPolicy.newServerPolicy();
        policy.setInputBufferSize(1024);
        policy.setMaxBinaryMessageBufferSize(1024);
        policy.setMaxTextMessageBufferSize(1024);

        // Event Driver factory
        EventDriverFactory factory = new EventDriverFactory(policy);

        // Local Socket
        EventDriver localDriver = factory.wrap(new DummySocket());

        // Remote socket & Session
        socket = new TrackingInputStreamSocket("remote");
        EventDriver remoteDriver = factory.wrap(socket);
        remoteSession = new LocalWebSocketSession(testname,remoteDriver);
        remoteSession.open();
        OutgoingFrames socketPipe = FramePipes.to(remoteDriver);

        // Local Session
        session = new LocalWebSocketSession(testname,localDriver);

        session.setPolicy(policy);
        // talk to our remote socket
        session.setOutgoingHandler(socketPipe);
        // open connection
        session.open();
    }

    @Test
    @Ignore
    public void testSimpleMessage() throws IOException
    {
        ByteBuffer data = BufferUtil.toBuffer("Hello World",StringUtil.__UTF8_CHARSET);
        session.getRemote().sendBytes(data);

        Assert.assertThat("Socket.messageQueue.size",socket.messageQueue.size(),is(1));
        String msg = socket.messageQueue.poll();
        Assert.assertThat("Message",msg,is("Hello World"));
    }
}
