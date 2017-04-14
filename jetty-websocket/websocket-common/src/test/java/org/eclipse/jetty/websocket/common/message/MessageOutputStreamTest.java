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

package org.eclipse.jetty.websocket.common.message;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import java.net.URI;
import java.util.Arrays;

import org.eclipse.jetty.toolchain.test.TestTracker;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.io.FramePipes;
import org.eclipse.jetty.websocket.common.io.LocalWebSocketConnection;
import org.eclipse.jetty.websocket.common.scopes.SimpleContainerScope;
import org.eclipse.jetty.websocket.common.scopes.WebSocketContainerScope;
import org.eclipse.jetty.websocket.common.test.LeakTrackingBufferPoolRule;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

public class MessageOutputStreamTest
{
    private static final Logger LOG = Log.getLogger(MessageOutputStreamTest.class);

    @Rule
    public TestTracker testtracker = new TestTracker();

    @Rule
    public TestName testname = new TestName();

    @Rule
    public LeakTrackingBufferPoolRule bufferPool = new LeakTrackingBufferPoolRule("Test");

    private WebSocketPolicy policy;
    private TrackingSocket remoteSocket;
    private WebSocketSession session;
    private WebSocketSession remoteSession;
    
    @After
    public void closeSession() throws Exception
    {
        session.close();
        session.stop();
        remoteSession.close();
        remoteSession.stop();
    }

    @Before
    public void setupSession() throws Exception
    {
        policy = WebSocketPolicy.newServerPolicy();
        policy.setInputBufferSize(1024);

        // Container
        WebSocketContainerScope containerScope = new SimpleContainerScope(policy,bufferPool);

        // remote socket
        remoteSocket = new TrackingSocket("remote");
        URI remoteURI = new URI("ws://localhost/remote");
        LocalWebSocketConnection remoteConnection = new LocalWebSocketConnection(remoteURI, bufferPool);
        remoteSession = new WebSocketSession(containerScope,remoteURI,remoteSocket,remoteConnection);
        remoteSession.start();
        remoteSession.connect();
        remoteSession.open();

        // Local Session
        TrackingSocket localSocket = new TrackingSocket("local");
        URI localURI = new URI("ws://localhost/local");
        LocalWebSocketConnection localConnection = new LocalWebSocketConnection(localURI, bufferPool);
        session = new WebSocketSession(containerScope,localURI,localSocket,localConnection);

        // talk to our remote socket
        session.setOutgoingHandler(FramePipes.to(remoteSession));
        session.connect();
        // start session
        session.start();
        // open connection
        session.open();
    }

    @Test(timeout = 2000)
    public void testMultipleWrites() throws Exception
    {
        try (MessageOutputStream stream = new MessageOutputStream(session))
        {
            stream.write("Hello".getBytes("UTF-8"));
            stream.write(" ".getBytes("UTF-8"));
            stream.write("World".getBytes("UTF-8"));
        }

        Assert.assertThat("Socket.messageQueue.size",remoteSocket.messageQueue.size(),is(1));
        String msg = remoteSocket.messageQueue.poll();
        Assert.assertThat("Message",msg,allOf(containsString("byte[11]"),containsString("Hello World")));
    }
    
    @Test(timeout = 2000)
    public void testSingleWrite() throws Exception
    {
        try (MessageOutputStream stream = new MessageOutputStream(session))
        {
            stream.write("Hello World".getBytes("UTF-8"));
        }

        Assert.assertThat("Socket.messageQueue.size",remoteSocket.messageQueue.size(),is(1));
        String msg = remoteSocket.messageQueue.poll();
        Assert.assertThat("Message",msg,allOf(containsString("byte[11]"),containsString("Hello World")));
    }
    
    @Test(timeout = 2000)
    public void testWriteMultipleBuffers() throws Exception
    {
        int bufsize = (int)(policy.getOutputBufferSize() * 2.5);
        byte buf[] = new byte[bufsize];
        LOG.debug("Buffer sizes: max:{}, test:{}",policy.getOutputBufferSize(),bufsize);
        Arrays.fill(buf,(byte)'x');
        buf[bufsize - 1] = (byte)'o'; // mark last entry for debugging

        try (MessageOutputStream stream = new MessageOutputStream(session))
        {
            stream.write(buf);
        }

        Assert.assertThat("Socket.messageQueue.size",remoteSocket.messageQueue.size(),is(1));
        String msg = remoteSocket.messageQueue.poll();
        Assert.assertThat("Message",msg,allOf(containsString("byte[" + bufsize + "]"),containsString("xxxo>>>")));
    }
}
