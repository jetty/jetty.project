//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.common.message.MessageOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.is;

public class MessageOutputStreamTest
{
    private static final Logger LOG = Log.getLogger(MessageOutputStreamTest.class);
    private static final int OUTPUT_BUFFER_SIZE = 4096;

    public TestableLeakTrackingBufferPool bufferPool = new TestableLeakTrackingBufferPool("Test");

    @AfterEach
    public void afterEach()
    {
        bufferPool.assertNoLeaks();
    }

    private OutgoingMessageCapture sessionCapture;

    @BeforeEach
    public void setupTest() throws Exception
    {
        sessionCapture = new OutgoingMessageCapture();
    }

    @Test
    public void testMultipleWrites() throws Exception
    {
        try (MessageOutputStream stream = new MessageOutputStream(sessionCapture, OUTPUT_BUFFER_SIZE, bufferPool))
        {
            stream.write("Hello".getBytes("UTF-8"));
            stream.write(" ".getBytes("UTF-8"));
            stream.write("World".getBytes("UTF-8"));
        }

        assertThat("Socket.binaryMessages.size", sessionCapture.binaryMessages.size(), is(1));
        ByteBuffer buffer = sessionCapture.binaryMessages.poll(1, TimeUnit.SECONDS);
        String message = BufferUtil.toUTF8String(buffer);
        assertThat("Message", message, is("Hello World"));
    }

    @Test
    public void testSingleWrite() throws Exception
    {
        try (MessageOutputStream stream = new MessageOutputStream(sessionCapture, OUTPUT_BUFFER_SIZE, bufferPool))
        {
            stream.write("Hello World".getBytes("UTF-8"));
        }

        assertThat("Socket.binaryMessages.size", sessionCapture.binaryMessages.size(), is(1));
        ByteBuffer buffer = sessionCapture.binaryMessages.poll(1, TimeUnit.SECONDS);
        String message = BufferUtil.toUTF8String(buffer);
        assertThat("Message", message, is("Hello World"));
    }

    @Test
    public void testWriteLarge_RequiringMultipleBuffers() throws Exception
    {
        int bufsize = (int)(OUTPUT_BUFFER_SIZE * 2.5);
        byte[] buf = new byte[bufsize];
        LOG.debug("Buffer sizes: max:{}, test:{}", OUTPUT_BUFFER_SIZE, bufsize);
        Arrays.fill(buf, (byte)'x');
        buf[bufsize - 1] = (byte)'o'; // mark last entry for debugging

        try (MessageOutputStream stream = new MessageOutputStream(sessionCapture, OUTPUT_BUFFER_SIZE, bufferPool))
        {
            stream.write(buf);
        }

        assertThat("Socket.binaryMessages.size", sessionCapture.binaryMessages.size(), is(1));
        ByteBuffer buffer = sessionCapture.binaryMessages.poll(1, TimeUnit.SECONDS);
        String message = BufferUtil.toUTF8String(buffer);
        assertThat("Message", message, endsWith("xxxxxo"));
    }
}
