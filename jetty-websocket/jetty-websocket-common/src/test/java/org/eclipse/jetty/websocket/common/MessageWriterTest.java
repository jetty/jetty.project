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

package org.eclipse.jetty.websocket.common;

import static org.hamcrest.Matchers.is;

import java.util.Arrays;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.common.message.MessageWriter;
import org.eclipse.jetty.websocket.core.LeakTrackingBufferPoolRule;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

public class MessageWriterTest
{
    private static final Logger LOG = Log.getLogger(MessageWriterTest.class);

    @Rule
    public TestName testname = new TestName();

    @Rule
    public LeakTrackingBufferPoolRule bufferPool = new LeakTrackingBufferPoolRule("Test");

    private WebSocketPolicy policy;
    private int bufferSize = 1024;
    private OutgoingMessageCapture remoteSocket;

    @Before
    public void setupSession() throws Exception
    {
        policy = WebSocketPolicy.newServerPolicy();
        policy.setInputBufferSize(1024);

        remoteSocket = new OutgoingMessageCapture(policy);
    }
    
    @Test
    public void testMultipleWrites() throws Exception
    {
        try (MessageWriter stream = new MessageWriter(remoteSocket, bufferSize))
        {
            stream.write("Hello");
            stream.write(" ");
            stream.write("World");
        }

        Assert.assertThat("Socket.messageQueue.size",remoteSocket.textMessages.size(),is(1));
        String msg = remoteSocket.textMessages.poll();
        Assert.assertThat("Message",msg,is("Hello World"));
    }
    
    @Test
    public void testSingleWrite() throws Exception
    {
        try (MessageWriter stream = new MessageWriter(remoteSocket, bufferSize))
        {
            stream.append("Hello World");
        }

        Assert.assertThat("Socket.messageQueue.size",remoteSocket.textMessages.size(),is(1));
        String msg = remoteSocket.textMessages.poll();
        Assert.assertThat("Message",msg,is("Hello World"));
    }
    
    @Test
    public void testWriteLarge_RequiringMultipleBuffers() throws Exception
    {
        int size = (int)(policy.getOutputBufferSize() * 2.5);
        char buf[] = new char[size];
        if (LOG.isDebugEnabled())
            LOG.debug("Buffer size: {}",size);
        Arrays.fill(buf,'x');
        buf[size - 1] = 'o'; // mark last entry for debugging

        try (MessageWriter stream = new MessageWriter(remoteSocket, bufferSize))
        {
            stream.write(buf);
        }

        Assert.assertThat("Socket.messageQueue.size",remoteSocket.textMessages.size(),is(1));
        String msg = remoteSocket.textMessages.poll();
        String expected = new String(buf);
        Assert.assertThat("Message",msg,is(expected));
    }
}
