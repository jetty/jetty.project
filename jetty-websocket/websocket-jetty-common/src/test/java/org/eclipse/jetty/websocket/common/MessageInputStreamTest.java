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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.websocket.common.message.MessageInputStream;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTimeout;

public class MessageInputStreamTest
{
    public TestableLeakTrackingBufferPool bufferPool = new TestableLeakTrackingBufferPool("Test");

    @AfterEach
    public void afterEach()
    {
        bufferPool.assertNoLeaks();
    }

    @Test
    public void testBasicAppendRead() throws IOException
    {
        assertTimeout(Duration.ofMillis(5000), () ->
        {
            try (MessageInputStream stream = new MessageInputStream())
            {
                // Append a single message (simple, short)
                Frame frame = new Frame(OpCode.TEXT);
                frame.setPayload("Hello World");
                frame.setFin(true);
                stream.accept(frame, Callback.NOOP);

                // Read entire message it from the stream.
                byte[] data = IO.readBytes(stream);
                String message = new String(data, 0, data.length, StandardCharsets.UTF_8);

                // Test it
                assertThat("Message", message, is("Hello World"));
            }
        });
    }

    @Test
    public void testBlockOnRead() throws Exception
    {
        assertTimeout(Duration.ofMillis(5000), () ->
        {
            try (MessageInputStream stream = new MessageInputStream())
            {
                final AtomicBoolean hadError = new AtomicBoolean(false);
                final CountDownLatch startLatch = new CountDownLatch(1);

                // This thread fills the stream (from the "worker" thread)
                // But slowly (intentionally).
                new Thread(() ->
                {
                    try
                    {
                        startLatch.countDown();
                        TimeUnit.MILLISECONDS.sleep(200);
                        stream.accept(new Frame(OpCode.BINARY).setPayload("Saved").setFin(false), Callback.NOOP);
                        TimeUnit.MILLISECONDS.sleep(200);
                        stream.accept(new Frame(OpCode.CONTINUATION).setPayload(" by ").setFin(false), Callback.NOOP);
                        TimeUnit.MILLISECONDS.sleep(200);
                        stream.accept(new Frame(OpCode.CONTINUATION).setPayload("Zero").setFin(true), Callback.NOOP);
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
        });
    }

    @Test
    public void testBlockOnReadInitial() throws IOException
    {
        assertTimeout(Duration.ofMillis(5000), () ->
        {
            try (MessageInputStream stream = new MessageInputStream())
            {
                final AtomicBoolean hadError = new AtomicBoolean(false);

                new Thread(() ->
                {
                    try
                    {
                        // wait for a little bit before populating buffers
                        TimeUnit.MILLISECONDS.sleep(400);
                        stream.accept(new Frame(OpCode.BINARY).setPayload("I will conquer").setFin(true), Callback.NOOP);
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
        });
    }

    @Test
    public void testReadByteNoBuffersClosed() throws IOException
    {
        assertTimeout(Duration.ofMillis(5000), () ->
        {
            try (MessageInputStream stream = new MessageInputStream())
            {
                final AtomicBoolean hadError = new AtomicBoolean(false);

                new Thread(() ->
                {
                    try
                    {
                        // wait for a little bit before sending input closed
                        TimeUnit.MILLISECONDS.sleep(400);
                        stream.close();
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

                // Test it
                assertThat("Error when closing", hadError.get(), is(false));
                assertThat("Initial byte (Should be EOF)", b, is(-1));
            }
        });
    }

    @Test
    public void testAppendEmptyPayloadRead() throws IOException
    {
        assertTimeout(Duration.ofMillis(5000), () ->
        {
            try (MessageInputStream stream = new MessageInputStream())
            {
                // Append parts of message
                Frame msg1 = new Frame(OpCode.BINARY).setPayload("Hello ").setFin(false);
                // what is being tested (an empty payload)
                Frame msg2 = new Frame(OpCode.CONTINUATION).setPayload(new byte[0]).setFin(false);
                Frame msg3 = new Frame(OpCode.CONTINUATION).setPayload("World").setFin(true);

                stream.accept(msg1, Callback.NOOP);
                stream.accept(msg2, Callback.NOOP);
                stream.accept(msg3, Callback.NOOP);

                // Read entire message it from the stream.
                byte[] data = IO.readBytes(stream);
                String message = new String(data, 0, data.length, StandardCharsets.UTF_8);

                // Test it
                assertThat("Message", message, is("Hello World"));
            }
        });
    }

    @Test
    public void testAppendNullPayloadRead() throws IOException
    {
        assertTimeout(Duration.ofMillis(5000), () ->
        {
            try (MessageInputStream stream = new MessageInputStream())
            {
                // Append parts of message
                Frame msg1 = new Frame(OpCode.BINARY).setPayload("Hello ").setFin(false);
                // what is being tested (a null payload)
                ByteBuffer nilPayload = null;
                Frame msg2 = new Frame(OpCode.CONTINUATION).setPayload(nilPayload).setFin(false);
                Frame msg3 = new Frame(OpCode.CONTINUATION).setPayload("World").setFin(true);

                stream.accept(msg1, Callback.NOOP);
                stream.accept(msg2, Callback.NOOP);
                stream.accept(msg3, Callback.NOOP);

                // Read entire message it from the stream.
                byte[] data = IO.readBytes(stream);
                String message = new String(data, 0, data.length, StandardCharsets.UTF_8);

                // Test it
                assertThat("Message", message, is("Hello World"));
            }
        });
    }
}
