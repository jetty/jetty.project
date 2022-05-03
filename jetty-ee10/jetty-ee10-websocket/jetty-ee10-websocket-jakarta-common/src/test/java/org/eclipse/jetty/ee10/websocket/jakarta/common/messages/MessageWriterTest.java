//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.websocket.jakarta.common.messages;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.internal.messages.MessageWriter;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class MessageWriterTest
{
    private ByteBufferPool bufferPool = new MappedByteBufferPool();

    @Test
    public void testSingleByteArray512b() throws IOException, InterruptedException
    {
        FrameCapture capture = new FrameCapture();
        capture.setOutputBufferSize(1024);
        try (MessageWriter writer = new MessageWriter(capture, bufferPool))
        {
            char[] cbuf = new char[512];
            Arrays.fill(cbuf, 'x');
            writer.write(cbuf);
        }

        Frame frame = capture.frames.poll(1, TimeUnit.SECONDS);
        assertThat("Frame[0].opcode", frame.getOpCode(), is(OpCode.TEXT));
        assertThat("Frame[0].payloadLength", frame.getPayloadLength(), is(512));
        assertThat("Frame[0].fin", frame.isFin(), is(true));
    }

    @Test
    public void testSingleByteArray2k() throws IOException, InterruptedException
    {
        FrameCapture capture = new FrameCapture();
        capture.setOutputBufferSize(1024);
        try (MessageWriter writer = new MessageWriter(capture, bufferPool))
        {
            char[] cbuf = new char[1024 * 2];
            Arrays.fill(cbuf, 'x');
            writer.write(cbuf);
        }

        Frame frame = capture.frames.poll(1, TimeUnit.SECONDS);
        assertThat("Frame[0].opcode", frame.getOpCode(), is(OpCode.TEXT));
        assertThat("Frame[0].payloadLength", frame.getPayloadLength(), is(1024));
        assertThat("Frame[0].fin", frame.isFin(), is(false));

        frame = capture.frames.poll(1, TimeUnit.SECONDS);
        assertThat("Frame[1].opcode", frame.getOpCode(), is(OpCode.CONTINUATION));
        assertThat("Frame[1].payloadLength", frame.getPayloadLength(), is(1024));
        assertThat("Frame[1].fin", frame.isFin(), is(true));
    }

    @Test
    public void testMultipleByteArrays2k() throws IOException, InterruptedException
    {
        final int testSize = 1024 * 2;
        final int outputSize = 80;
        final int writerBufferSize = 100;

        FrameCapture capture = new FrameCapture();
        capture.setOutputBufferSize(writerBufferSize);
        try (MessageWriter writer = new MessageWriter(capture, bufferPool))
        {
            char[] cbuf = new char[testSize];
            Arrays.fill(cbuf, 'x');
            int remaining = cbuf.length;
            int offset = 0;
            while (remaining > 0)
            {
                int length = Math.min(remaining, outputSize);
                writer.write(cbuf, offset, length);
                remaining -= outputSize;
            }
        }

        Frame frame;

        int remaining = testSize;
        byte expectedOpCode = OpCode.TEXT;
        int i = 0;

        while (remaining > 0)
        {
            frame = capture.frames.poll(1, TimeUnit.SECONDS);
            String prefix = String.format("Frame[%d](op=%d,%sfin)", (i++), frame.getOpCode(), frame.isFin() ? "" : "!");
            assertThat(prefix + ".opcode", frame.getOpCode(), is(expectedOpCode));
            int expectedSize = Math.min(remaining, writerBufferSize);
            assertThat(prefix + ".payloadLength", frame.getPayloadLength(), is(expectedSize));
            boolean expectedFin = (remaining < outputSize);
            assertThat(prefix + ".fin", frame.isFin(), is(expectedFin));

            expectedOpCode = OpCode.CONTINUATION;
            remaining -= frame.getPayloadLength();
        }
    }

    @Test
    public void testSlightLargerThenBufferSize() throws IOException, InterruptedException
    {
        final int writerBufferSize = 64 * 1024;
        final int testSize = writerBufferSize + 16;

        WholeMessageCapture capture = new WholeMessageCapture();
        capture.setOutputBufferSize(writerBufferSize);
        try (MessageWriter writer = new MessageWriter(capture, bufferPool))
        {
            char[] cbuf = new char[testSize];
            Arrays.fill(cbuf, 'x');
            for (int i = 0; i < testSize; i++)
            {
                writer.write(cbuf[i]);
            }
        }

        String message = capture.messages.poll(1, TimeUnit.SECONDS);
        assertThat("Message[0].length", message.length(), is(testSize));
    }

    public static class FrameCapture extends CoreSession.Empty
    {
        public BlockingQueue<Frame> frames = new LinkedBlockingQueue<>();

        @Override
        public void sendFrame(Frame frame, Callback callback, boolean batch)
        {
            frames.offer(Frame.copy(frame));
            callback.succeeded();
        }
    }

    public static class WholeMessageCapture extends CoreSession.Empty
    {
        public BlockingQueue<String> messages = new LinkedBlockingQueue<>();

        private Utf8StringBuilder activeMessage;

        @Override
        public void sendFrame(Frame frame, Callback callback, boolean batch)
        {
            if (frame.getOpCode() == OpCode.TEXT)
                activeMessage = new Utf8StringBuilder();

            activeMessage.append(frame.getPayload().slice());

            if (frame.isFin())
            {
                messages.offer(activeMessage.toString());
                activeMessage = null;
            }
            callback.succeeded();
        }
    }
}
