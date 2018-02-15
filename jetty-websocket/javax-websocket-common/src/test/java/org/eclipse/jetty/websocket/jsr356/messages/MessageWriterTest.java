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

package org.eclipse.jetty.websocket.jsr356.messages;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.frames.OpCode;
import org.eclipse.jetty.websocket.core.frames.WebSocketFrame;
import org.eclipse.jetty.websocket.core.io.BatchMode;
import org.eclipse.jetty.websocket.jsr356.DummyChannel;
import org.junit.Test;

public class MessageWriterTest
{
    @Test
    public void testSingleByteArray_512b() throws IOException, InterruptedException
    {
        FrameCapture capture = new FrameCapture();
        try (MessageWriter writer = new MessageWriter(capture, 1024))
        {
            char cbuf[] = new char[512];
            Arrays.fill(cbuf, 'x');
            writer.write(cbuf);
        }

        WebSocketFrame frame = capture.frames.poll(1, TimeUnit.SECONDS);
        assertThat("Frame[0].opcode", frame.getOpCode(), is(OpCode.TEXT));
        assertThat("Frame[0].payloadLength", frame.getPayloadLength(), is(512));
        assertThat("Frame[0].fin", frame.isFin(), is(true));
    }

    @Test
    public void testSingleByteArray_2k() throws IOException, InterruptedException
    {
        FrameCapture capture = new FrameCapture();
        try (MessageWriter writer = new MessageWriter(capture, 1024))
        {
            char cbuf[] = new char[1024 * 2];
            Arrays.fill(cbuf, 'x');
            writer.write(cbuf);
        }

        WebSocketFrame frame = capture.frames.poll(1, TimeUnit.SECONDS);
        assertThat("Frame[0].opcode", frame.getOpCode(), is(OpCode.TEXT));
        assertThat("Frame[0].payloadLength", frame.getPayloadLength(), is(1024));
        assertThat("Frame[0].fin", frame.isFin(), is(false));

        frame = capture.frames.poll(1, TimeUnit.SECONDS);
        assertThat("Frame[1].opcode", frame.getOpCode(), is(OpCode.CONTINUATION));
        assertThat("Frame[1].payloadLength", frame.getPayloadLength(), is(1024));
        assertThat("Frame[1].fin", frame.isFin(), is(true));
    }

    @Test
    public void testMultipleByteArrays_2k() throws IOException, InterruptedException
    {
        final int testSize = 1024 * 2;
        final int outputSize = 80;
        final int writerBufferSize = 100;

        FrameCapture capture = new FrameCapture();
        try (MessageWriter writer = new MessageWriter(capture, writerBufferSize))
        {
            char cbuf[] = new char[testSize];
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

        WebSocketFrame frame;

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
        try (MessageWriter writer = new MessageWriter(capture, writerBufferSize))
        {
            char cbuf[] = new char[testSize];
            Arrays.fill(cbuf, 'x');
            for (int i = 0; i < testSize; i++)
            {
                writer.write(cbuf[i]);
            }
        }

        String message = capture.messages.poll(1, TimeUnit.SECONDS);
        assertThat("Message[0].length", message.length(), is(testSize));
    }

    public static class FrameCapture extends DummyChannel
    {
        public BlockingQueue<WebSocketFrame> frames = new LinkedBlockingQueue<>();

        @Override
        public void sendFrame(Frame frame, Callback callback, BatchMode batchMode)
        {
            frames.offer(WebSocketFrame.copy(frame));
            callback.succeeded();
        }
    }

    public static class WholeMessageCapture extends DummyChannel
    {
        public BlockingQueue<String> messages = new LinkedBlockingQueue<>();

        private Utf8StringBuilder activeMessage;

        @Override
        public void sendFrame(Frame frame, Callback callback, BatchMode batchMode)
        {
            if (frame.getOpCode() == OpCode.TEXT)
                activeMessage = new Utf8StringBuilder();

            activeMessage.append(frame.getPayload());

            if (frame.isFin())
            {
                messages.offer(activeMessage.toString());
                activeMessage = null;
            }
            callback.succeeded();
        }
    }
}
