//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.common;

import java.util.Arrays;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.common.message.MessageWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class MessageWriterTest
{
    private static final Logger LOG = Log.getLogger(MessageWriterTest.class);
    private static final int OUTPUT_BUFFER_SIZE = 4096;

    public TestableLeakTrackingBufferPool bufferPool = new TestableLeakTrackingBufferPool("Test");

    @AfterEach
    public void afterEach()
    {
        bufferPool.assertNoLeaks();
    }

    private OutgoingMessageCapture remoteSocket;

    @BeforeEach
    public void setupSession()
    {
        remoteSocket = new OutgoingMessageCapture();
    }

    @Test
    public void testMultipleWrites() throws Exception
    {
        try (MessageWriter stream = new MessageWriter(remoteSocket, OUTPUT_BUFFER_SIZE))
        {
            stream.write("Hello");
            stream.write(" ");
            stream.write("World");
        }

        assertThat("Socket.messageQueue.size", remoteSocket.textMessages.size(), is(1));
        String msg = remoteSocket.textMessages.poll();
        assertThat("Message", msg, is("Hello World"));
    }

    @Test
    public void testSingleWrite() throws Exception
    {
        try (MessageWriter stream = new MessageWriter(remoteSocket, OUTPUT_BUFFER_SIZE))
        {
            stream.append("Hello World");
        }

        assertThat("Socket.messageQueue.size", remoteSocket.textMessages.size(), is(1));
        String msg = remoteSocket.textMessages.poll();
        assertThat("Message", msg, is("Hello World"));
    }

    @Test
    public void testWriteLargeRequiringMultipleBuffers() throws Exception
    {
        int size = (int)(OUTPUT_BUFFER_SIZE * 2.5);
        char[] buf = new char[size];
        if (LOG.isDebugEnabled())
            LOG.debug("Buffer size: {}", size);
        Arrays.fill(buf, 'x');
        buf[size - 1] = 'o'; // mark last entry for debugging

        try (MessageWriter stream = new MessageWriter(remoteSocket, OUTPUT_BUFFER_SIZE))
        {
            stream.write(buf);
        }

        assertThat("Socket.messageQueue.size", remoteSocket.textMessages.size(), is(1));
        String msg = remoteSocket.textMessages.poll();
        String expected = new String(buf);
        assertThat("Message", msg, is(expected));
    }
}
