//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.websocket.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.messages.MessageInputStream;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;

public class MessageInputStreamTest
{
    @Test
    public void testBasicAppendRead() throws IOException
    {
        assertTimeout(Duration.ofMillis(5000), () ->
        {
            // A single message (simple, short)
            try (MessageInputStream stream = new MessageInputStream(new CoreSession.Empty()))
            {
                try (Frame frame = new Frame(OpCode.TEXT, "Hello World"))
                {
                    stream.accept(frame, Callback.NOOP);

                    // Read entire message it from the stream.
                    byte[] data = IO.readBytes(stream);
                    String message = new String(data, StandardCharsets.UTF_8);

                    // Test it
                    assertThat("Message", message, is("Hello World"));
                }
            }
        });
    }

    @Test
    public void testMultipleReadsIntoSingleByteArray() throws IOException
    {
        // A single message (simple, short)
        try (MessageInputStream stream = new MessageInputStream(new CoreSession.Empty()))
        {
            try (Frame frame = new Frame(OpCode.TEXT, "Hello World"))
            {
                stream.accept(frame, Callback.NOOP);

                // Read entire message it from the stream.
                byte[] bytes = new byte[100];

                int read = stream.read(bytes, 0, 6);
                assertThat(read, is(6));

                read = stream.read(bytes, 6, 10);
                assertThat(read, is(5));

                read = stream.read(bytes, 11, 10);
                assertThat(read, is(-1));

                String message = new String(bytes, 0, 11, StandardCharsets.UTF_8);

                // Test it
                assertThat("Message", message, is("Hello World"));
            }
        }
    }

    @Test
    public void testBlockOnRead() throws Exception
    {
        assertTimeout(Duration.ofMillis(5000), () ->
        {
            try (MessageInputStream stream = new MessageInputStream(new CoreSession.Empty()))
            {
                try (Frame frame1 = new Frame(OpCode.BINARY, false, "Saved");
                     Frame frame2 = new Frame(OpCode.CONTINUATION, false, " by ");
                     Frame frame3 = new Frame(OpCode.CONTINUATION, "Zero"))
                {
                    AtomicBoolean hadError = new AtomicBoolean(false);
                    CountDownLatch startLatch = new CountDownLatch(1);

                    // This thread fills the stream (from the "worker" thread)
                    // But slowly (intentionally).
                    new Thread(() ->
                    {
                        try
                        {
                            startLatch.countDown();
                            TimeUnit.MILLISECONDS.sleep(200);
                            stream.accept(frame1, Callback.NOOP);
                            TimeUnit.MILLISECONDS.sleep(200);
                            stream.accept(frame2, Callback.NOOP);
                            TimeUnit.MILLISECONDS.sleep(200);
                            stream.accept(frame3, Callback.NOOP);
                        }
                        catch (InterruptedException e)
                        {
                            hadError.set(true);
                            e.printStackTrace(System.err);
                        }
                    }).start();

                    // wait for thread to start
                    startLatch.await();

                    // Read it from the stream.
                    byte[] data = IO.readBytes(stream);
                    String message = new String(data, 0, data.length, StandardCharsets.UTF_8);

                    // Test it
                    assertThat("Error when appending", hadError.get(), is(false));
                    assertThat("Message", message, is("Saved by Zero"));
                }
            }
        });
    }

    @Test
    public void testBlockOnReadInitial() throws IOException
    {
        assertTimeout(Duration.ofMillis(5000), () ->
        {
            try (MessageInputStream stream = new MessageInputStream(new CoreSession.Empty()))
            {
                try (Frame frame = new Frame(OpCode.BINARY, "I will conquer"))
                {
                    AtomicBoolean hadError = new AtomicBoolean(false);
                    new Thread(() ->
                    {
                        try
                        {
                            // wait for a little bit before populating buffers
                            TimeUnit.MILLISECONDS.sleep(400);
                            stream.accept(frame, Callback.NOOP);
                        }
                        catch (InterruptedException e)
                        {
                            hadError.set(true);
                            e.printStackTrace(System.err);
                        }
                    }).start();

                    // Read byte from stream.
                    int b = stream.read();
                    // Should be a byte, blocking till byte received.

                    // Test it
                    assertThat("Error when appending", hadError.get(), is(false));
                    assertThat("Initial byte", b, is((int)'I'));
                }
            }
        });
    }

    @Test
    public void testReadByteNoBuffersClosed() throws IOException
    {
        assertTimeout(Duration.ofMillis(5000), () ->
        {
            try (MessageInputStream stream = new MessageInputStream(new CoreSession.Empty()))
            {
                try (Frame emptyFrame = new Frame(OpCode.TEXT))
                {
                    AtomicBoolean hadError = new AtomicBoolean(false);
                    new Thread(() ->
                    {
                        try
                        {
                            // wait for a little bit before sending input closed
                            TimeUnit.MILLISECONDS.sleep(400);
                            stream.accept(emptyFrame, Callback.NOOP);
                        }
                        catch (Throwable t)
                        {
                            hadError.set(true);
                            t.printStackTrace(System.err);
                        }
                    }).start();

                    // Read byte from stream.
                    int b = stream.read();

                    // Should be a -1, indicating the end of the stream.
                    assertThat("Error when closing", hadError.get(), is(false));
                    assertThat("Initial byte (Should be EOF)", b, is(-1));

                    // Close the stream.
                    stream.close();

                    // Any frame content after stream is closed should be discarded, and the callback succeeded.
                    try (Frame frame = new Frame(OpCode.TEXT, "hello world"))
                    {
                        FutureCallback callback = new FutureCallback();
                        stream.accept(frame, callback);
                        callback.block(5, TimeUnit.SECONDS);
                    }

                    // Any read after the stream is closed leads to an IOException.
                    IOException error = assertThrows(IOException.class, stream::read);
                    assertThat(error.getMessage(), is("Closed"));
                }
            }
        });
    }

    @Test
    public void testAppendEmptyPayloadRead() throws IOException
    {
        assertTimeout(Duration.ofMillis(5000), () ->
        {
            // Parts of message, what is being tested is the empty payload of msg2
            try (MessageInputStream stream = new MessageInputStream(new CoreSession.Empty()))
            {
                try (Frame msg1 = new Frame(OpCode.BINARY, false, "Hello ");
                     Frame msg2 = new Frame(OpCode.CONTINUATION, false, RetainableByteBuffer.empty());
                     Frame msg3 = new Frame(OpCode.CONTINUATION, "World"))
                {
                    stream.accept(msg1, Callback.NOOP);
                    stream.accept(msg2, Callback.NOOP);
                    stream.accept(msg3, Callback.NOOP);

                    // Read entire message it from the stream.
                    byte[] data = IO.readBytes(stream);
                    String message = new String(data, 0, data.length, StandardCharsets.UTF_8);

                    // Test it
                    assertThat("Message", message, is("Hello World"));
                }
            }
        });
    }

    @Test
    public void testReadSingleByteIsNotSigned() throws Exception
    {
        // Byte must be greater than 127.
        int theByte = 200;
        // A single message (simple, short)
        try (MessageInputStream stream = new MessageInputStream(new CoreSession.Empty());
             Frame frame = new Frame(OpCode.BINARY, RetainableByteBuffer.wrap(new byte[]{(byte)theByte})))
        {
            stream.accept(frame, Callback.NOOP);

            // Single byte read must not return a signed byte.
            int read = stream.read();
            assertEquals(theByte, read);
        }
    }
}
