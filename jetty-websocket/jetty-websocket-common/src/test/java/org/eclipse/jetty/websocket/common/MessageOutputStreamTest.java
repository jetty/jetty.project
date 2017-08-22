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

package org.eclipse.jetty.websocket.common;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.is;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.common.message.MessageOutputStream;
import org.eclipse.jetty.websocket.core.LeakTrackingBufferPoolRule;
import org.eclipse.jetty.websocket.core.WSPolicy;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

public class MessageOutputStreamTest
{
    private static final Logger LOG = Log.getLogger(MessageOutputStreamTest.class);

    @Rule
    public TestName testname = new TestName();

    @Rule
    public LeakTrackingBufferPoolRule bufferPool = new LeakTrackingBufferPoolRule("Test");

    private WSPolicy policy;
    private OutgoingMessageCapture remoteSocket;

    @Before
    public void setupTest() throws Exception
    {
        policy = WSPolicy.newServerPolicy();
        policy.setInputBufferSize(1024);

        remoteSocket = new OutgoingMessageCapture(policy);
    }

    @Test(timeout = 2000)
    public void testMultipleWrites() throws Exception
    {
        try (MessageOutputStream stream = new MessageOutputStream(remoteSocket, policy.getOutputBufferSize(), bufferPool))
        {
            stream.write("Hello".getBytes("UTF-8"));
            stream.write(" ".getBytes("UTF-8"));
            stream.write("World".getBytes("UTF-8"));
        }

        Assert.assertThat("Socket.messageQueue.size", remoteSocket.binaryMessages.size(), is(1));
        ByteBuffer buffer = remoteSocket.binaryMessages.poll(1, TimeUnit.SECONDS);
        String message = BufferUtil.toUTF8String(buffer);
        Assert.assertThat("Message", message, is("Hello World"));
    }

    @Test(timeout = 2000)
    public void testSingleWrite() throws Exception
    {
        try (MessageOutputStream stream = new MessageOutputStream(remoteSocket, policy.getOutputBufferSize(), bufferPool))
        {
            stream.write("Hello World".getBytes("UTF-8"));
        }

        Assert.assertThat("Socket.messageQueue.size", remoteSocket.binaryMessages.size(), is(1));
        ByteBuffer buffer = remoteSocket.binaryMessages.poll(1, TimeUnit.SECONDS);
        String message = BufferUtil.toUTF8String(buffer);
        Assert.assertThat("Message", message, is("Hello World"));
    }

    @Test(timeout = 2000000)
    public void testWriteMultipleBuffers() throws Exception
    {
        int bufsize = (int) (policy.getOutputBufferSize() * 2.5);
        byte buf[] = new byte[bufsize];
        LOG.debug("Buffer sizes: max:{}, test:{}", policy.getOutputBufferSize(), bufsize);
        Arrays.fill(buf, (byte) 'x');
        buf[bufsize - 1] = (byte) 'o'; // mark last entry for debugging

        try (MessageOutputStream stream = new MessageOutputStream(remoteSocket, policy.getOutputBufferSize(), bufferPool))
        {
            stream.write(buf);
        }

        Assert.assertThat("Socket.messageQueue.size", remoteSocket.binaryMessages.size(), is(1));
        ByteBuffer buffer = remoteSocket.binaryMessages.poll(1, TimeUnit.SECONDS);
        String message = BufferUtil.toUTF8String(buffer);
        Assert.assertThat("Message", message, endsWith("xxxxxo"));
    }
}
