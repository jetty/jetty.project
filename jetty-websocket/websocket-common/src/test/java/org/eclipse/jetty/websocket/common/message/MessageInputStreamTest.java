//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.websocket.api.SuspendToken;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static java.time.Duration.ofSeconds;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@ExtendWith(WorkDirExtension.class)
public class MessageInputStreamTest
{
    public WorkDir testdir;

    public ByteBufferPool bufferPool = new MappedByteBufferPool();

    @Test
    public void testBasicAppendRead() throws IOException
    {
        StreamTestSession session = new StreamTestSession();
        MessageInputStream stream = new MessageInputStream(session);
        session.setMessageInputStream(stream);

        // Append a single message (simple, short)
        ByteBuffer payload = BufferUtil.toBuffer("Hello World!", StandardCharsets.UTF_8);
        session.addContent(payload, true);
        session.provideContent();

        // Read entire message it from the stream.
        byte[] buf = new byte[32];
        int len = stream.read(buf);
        String message = new String(buf, 0, len, StandardCharsets.UTF_8);

        // Test it
        assertThat("Message", message, is("Hello World!"));
    }

    @Test
    public void testBlockOnRead() throws Exception
    {
        StreamTestSession session = new StreamTestSession();
        MessageInputStream stream = new MessageInputStream(session);
        session.setMessageInputStream(stream);
        new Thread(session::provideContent).start();

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
                session.addContent("Saved", false);
                TimeUnit.MILLISECONDS.sleep(200);
                session.addContent(" by ", false);
                TimeUnit.MILLISECONDS.sleep(200);
                session.addContent("Zero", false);
                TimeUnit.MILLISECONDS.sleep(200);
                session.addContent("", true);
            }
            catch (Throwable t)
            {
                hadError.set(true);
                t.printStackTrace(System.err);
            }
        }).start();

        Assertions.assertTimeoutPreemptively(ofSeconds(5), () ->
        {
            // wait for thread to start
            startLatch.await();

            // Read it from the stream.
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            IO.copy(stream, out);
            byte[] bytes = out.toByteArray();
            String message = new String(bytes, 0, bytes.length, StandardCharsets.UTF_8);

            // Test it
            assertThat("Error when appending", hadError.get(), is(false));
            assertThat("Message", message, is("Saved by Zero"));
        });
    }

    @Test
    public void testBlockOnReadInitial() throws IOException
    {
        StreamTestSession session = new StreamTestSession();
        MessageInputStream stream = new MessageInputStream(session);
        session.setMessageInputStream(stream);
        session.addContent("I will conquer", true);

        AtomicReference<Throwable> error = new AtomicReference<>();
        new Thread(() ->
        {
            try
            {
                // wait for a little bit before initiating write to stream
                TimeUnit.MILLISECONDS.sleep(1000);
                session.provideContent();
            }
            catch (Throwable t)
            {
                error.set(t);
                t.printStackTrace(System.err);
            }
        }).start();

        Assertions.assertTimeoutPreemptively(ofSeconds(10), () ->
        {
            // Read byte from stream, block until byte received.
            int b = stream.read();
            assertThat("Initial byte", b, is((int)'I'));

            // No error occurred.
            assertNull(error.get());
        });
    }

    @Test
    public void testReadByteNoBuffersClosed() throws IOException
    {
        try (MessageInputStream stream = new MessageInputStream(new EmptySession()))
        {
            final AtomicBoolean hadError = new AtomicBoolean(false);

            new Thread(() ->
            {
                try
                {
                    // wait for a little bit before sending input closed
                    TimeUnit.MILLISECONDS.sleep(1000);
                    stream.appendFrame(null, true);
                    stream.messageComplete();
                }
                catch (InterruptedException | IOException e)
                {
                    hadError.set(true);
                    e.printStackTrace(System.err);
                }
            }).start();

            Assertions.assertTimeoutPreemptively(ofSeconds(10), () ->
            {
                // Read byte from stream. Should be a -1, indicating the end of the stream.
                int b = stream.read();
                assertThat("Initial byte", b, is(-1));

                // No error occurred.
                assertThat("Error when appending", hadError.get(), is(false));
            });
        }
    }

    @Test
    public void testSplitMessageWithEmptyPayloads() throws IOException
    {
        StreamTestSession session = new StreamTestSession();
        MessageInputStream stream = new MessageInputStream(session);
        session.setMessageInputStream(stream);

        session.addContent("", false);
        session.addContent("Hello", false);
        session.addContent("", false);
        session.addContent(" World", false);
        session.addContent("!", false);
        session.addContent("", true);
        session.provideContent();

        // Read entire message it from the stream.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        IO.copy(stream, out);
        byte[] bytes = out.toByteArray();
        String message = new String(bytes, 0, bytes.length, StandardCharsets.UTF_8);

        // Test it
        assertThat("Message", message, is("Hello World!"));
    }

    @Test
    public void testReadBeforeFirstAppend() throws IOException
    {
        StreamTestSession session = new StreamTestSession();
        MessageInputStream stream = new MessageInputStream(session);
        session.setMessageInputStream(stream);

        // Append a single message (simple, short)
        session.addContent(BufferUtil.EMPTY_BUFFER, false);
        session.addContent("Hello World", true);

        new Thread(() ->
        {
            try
            {
                Thread.sleep(2000);
                session.provideContent();
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
        }).start();

        // Read entire message it from the stream.
        byte[] buf = new byte[32];
        int len = stream.read(buf);
        String message = new String(buf, 0, len, StandardCharsets.UTF_8);

        // Test it
        assertThat("Message", message, is("Hello World"));
    }

    public static class StreamTestSession extends EmptySession
    {
        private static final ByteBuffer EOF = BufferUtil.allocate(0);
        private final AtomicBoolean suspended = new AtomicBoolean(false);
        private BlockingArrayQueue<ByteBuffer> contentQueue = new BlockingArrayQueue<>();
        private MessageInputStream stream;

        public void setMessageInputStream(MessageInputStream stream)
        {
            this.stream = stream;
        }

        public void addContent(String content, boolean last)
        {
            addContent(BufferUtil.toBuffer(content, StandardCharsets.UTF_8), last);
        }

        public void addContent(ByteBuffer content, boolean last)
        {
            contentQueue.add(content);
            if (last)
                contentQueue.add(EOF);
        }

        public void provideContent()
        {
            pollAndAppendFrame();
        }

        @Override
        public void resume()
        {
            if (!suspended.compareAndSet(true, false))
                throw new IllegalStateException();
            pollAndAppendFrame();
        }

        @Override
        public SuspendToken suspend()
        {
            if (!suspended.compareAndSet(false, true))
                throw new IllegalStateException();
            return super.suspend();
        }

        private void pollAndAppendFrame()
        {
            try
            {
                while (true)
                {
                    ByteBuffer content = contentQueue.poll(10, TimeUnit.SECONDS);
                    assertNotNull(content);

                    boolean eof = (content == EOF);
                    stream.appendFrame(content, eof);
                    if (eof)
                    {
                        stream.messageComplete();
                        break;
                    }

                    if (suspended.get())
                        break;
                }
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
        }
    }
}
