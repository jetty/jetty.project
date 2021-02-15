//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.server.handler.gzip.GzipHttpInputInterceptor;
import org.eclipse.jetty.util.compression.InflaterPool;
import org.eclipse.jetty.util.thread.AutoLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.core.Is.is;

public class BlockingContentProducerTest
{
    private ScheduledExecutorService scheduledExecutorService;
    private InflaterPool inflaterPool;

    @BeforeEach
    public void setUp()
    {
        scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
        inflaterPool = new InflaterPool(-1, true);
    }

    @AfterEach
    public void tearDown()
    {
        scheduledExecutorService.shutdownNow();
    }

    @Test
    public void testBlockingContentProducerNoInterceptor()
    {
        ByteBuffer[] buffers = new ByteBuffer[3];
        buffers[0] = ByteBuffer.wrap("1 hello 1".getBytes(StandardCharsets.ISO_8859_1));
        buffers[1] = ByteBuffer.wrap("2 howdy 2".getBytes(StandardCharsets.ISO_8859_1));
        buffers[2] = ByteBuffer.wrap("3 hey ya 3".getBytes(StandardCharsets.ISO_8859_1));
        final int totalContentBytesCount = countRemaining(buffers);
        final String originalContentString = asString(buffers);

        ContentListener contentListener = new ContentListener();
        ArrayDelayedHttpChannel httpChannel = new ArrayDelayedHttpChannel(buffers, new HttpInput.EofContent(), scheduledExecutorService, contentListener);
        ContentProducer contentProducer = new BlockingContentProducer(new AsyncContentProducer(httpChannel));
        contentListener.setContentProducer(contentProducer);

        try (AutoLock lock = contentProducer.lock())
        {
            Throwable error = readAndAssertContent(totalContentBytesCount, originalContentString, buffers.length + 1, contentProducer);
            assertThat(error, nullValue());
        }
    }

    @Test
    public void testBlockingContentProducerNoInterceptorWithError()
    {
        ByteBuffer[] buffers = new ByteBuffer[3];
        buffers[0] = ByteBuffer.wrap("1 hello 1".getBytes(StandardCharsets.ISO_8859_1));
        buffers[1] = ByteBuffer.wrap("2 howdy 2".getBytes(StandardCharsets.ISO_8859_1));
        buffers[2] = ByteBuffer.wrap("3 hey ya 3".getBytes(StandardCharsets.ISO_8859_1));
        final int totalContentBytesCount = countRemaining(buffers);
        final String originalContentString = asString(buffers);
        final Throwable expectedError = new EofException("Early EOF");

        ContentListener contentListener = new ContentListener();
        ArrayDelayedHttpChannel httpChannel = new ArrayDelayedHttpChannel(buffers, new HttpInput.ErrorContent(expectedError), scheduledExecutorService, contentListener);
        ContentProducer contentProducer = new BlockingContentProducer(new AsyncContentProducer(httpChannel));
        contentListener.setContentProducer(contentProducer);

        try (AutoLock lock = contentProducer.lock())
        {
            Throwable error = readAndAssertContent(totalContentBytesCount, originalContentString, buffers.length + 1, contentProducer);
            assertThat(error, is(expectedError));
        }
    }

    @Test
    public void testBlockingContentProducerGzipInterceptor()
    {
        ByteBuffer[] uncompressedBuffers = new ByteBuffer[3];
        uncompressedBuffers[0] = ByteBuffer.wrap("1 hello 1".getBytes(StandardCharsets.ISO_8859_1));
        uncompressedBuffers[1] = ByteBuffer.wrap("2 howdy 2".getBytes(StandardCharsets.ISO_8859_1));
        uncompressedBuffers[2] = ByteBuffer.wrap("3 hey ya 3".getBytes(StandardCharsets.ISO_8859_1));
        final int totalContentBytesCount = countRemaining(uncompressedBuffers);
        final String originalContentString = asString(uncompressedBuffers);

        ByteBuffer[] buffers = new ByteBuffer[3];
        buffers[0] = gzipByteBuffer(uncompressedBuffers[0]);
        buffers[1] = gzipByteBuffer(uncompressedBuffers[1]);
        buffers[2] = gzipByteBuffer(uncompressedBuffers[2]);

        ContentListener contentListener = new ContentListener();
        ArrayDelayedHttpChannel httpChannel = new ArrayDelayedHttpChannel(buffers, new HttpInput.EofContent(), scheduledExecutorService, contentListener);
        ContentProducer contentProducer = new BlockingContentProducer(new AsyncContentProducer(httpChannel));
        contentListener.setContentProducer(contentProducer);

        try (AutoLock lock = contentProducer.lock())
        {
            contentProducer.setInterceptor(new GzipHttpInputInterceptor(inflaterPool, new ArrayByteBufferPool(1, 1, 2), 32));

            Throwable error = readAndAssertContent(totalContentBytesCount, originalContentString, buffers.length + 1, contentProducer);
            assertThat(error, nullValue());
        }
    }

    @Test
    public void testBlockingContentProducerGzipInterceptorWithTinyBuffers()
    {
        ByteBuffer[] uncompressedBuffers = new ByteBuffer[3];
        uncompressedBuffers[0] = ByteBuffer.wrap("1 hello 1".getBytes(StandardCharsets.ISO_8859_1));
        uncompressedBuffers[1] = ByteBuffer.wrap("2 howdy 2".getBytes(StandardCharsets.ISO_8859_1));
        uncompressedBuffers[2] = ByteBuffer.wrap("3 hey ya 3".getBytes(StandardCharsets.ISO_8859_1));
        final int totalContentBytesCount = countRemaining(uncompressedBuffers);
        final String originalContentString = asString(uncompressedBuffers);

        ByteBuffer[] buffers = new ByteBuffer[3];
        buffers[0] = gzipByteBuffer(uncompressedBuffers[0]);
        buffers[1] = gzipByteBuffer(uncompressedBuffers[1]);
        buffers[2] = gzipByteBuffer(uncompressedBuffers[2]);

        ContentListener contentListener = new ContentListener();
        ArrayDelayedHttpChannel httpChannel = new ArrayDelayedHttpChannel(buffers, new HttpInput.EofContent(), scheduledExecutorService, contentListener);
        ContentProducer contentProducer = new BlockingContentProducer(new AsyncContentProducer(httpChannel));
        contentListener.setContentProducer(contentProducer);

        try (AutoLock lock = contentProducer.lock())
        {
            contentProducer.setInterceptor(new GzipHttpInputInterceptor(inflaterPool, new ArrayByteBufferPool(1, 1, 2), 1));

            Throwable error = readAndAssertContent(totalContentBytesCount, originalContentString, totalContentBytesCount + 1, contentProducer);
            assertThat(error, nullValue());
        }
    }

    @Test
    public void testBlockingContentProducerGzipInterceptorWithError()
    {
        ByteBuffer[] uncompressedBuffers = new ByteBuffer[3];
        uncompressedBuffers[0] = ByteBuffer.wrap("1 hello 1".getBytes(StandardCharsets.ISO_8859_1));
        uncompressedBuffers[1] = ByteBuffer.wrap("2 howdy 2".getBytes(StandardCharsets.ISO_8859_1));
        uncompressedBuffers[2] = ByteBuffer.wrap("3 hey ya 3".getBytes(StandardCharsets.ISO_8859_1));
        final int totalContentBytesCount = countRemaining(uncompressedBuffers);
        final String originalContentString = asString(uncompressedBuffers);
        final Throwable expectedError = new Throwable("HttpInput idle timeout");

        ByteBuffer[] buffers = new ByteBuffer[3];
        buffers[0] = gzipByteBuffer(uncompressedBuffers[0]);
        buffers[1] = gzipByteBuffer(uncompressedBuffers[1]);
        buffers[2] = gzipByteBuffer(uncompressedBuffers[2]);

        ContentListener contentListener = new ContentListener();
        ArrayDelayedHttpChannel httpChannel = new ArrayDelayedHttpChannel(buffers, new HttpInput.ErrorContent(expectedError), scheduledExecutorService, contentListener);
        ContentProducer contentProducer = new BlockingContentProducer(new AsyncContentProducer(httpChannel));
        contentListener.setContentProducer(contentProducer);

        try (AutoLock lock = contentProducer.lock())
        {
            contentProducer.setInterceptor(new GzipHttpInputInterceptor(inflaterPool, new ArrayByteBufferPool(1, 1, 2), 32));

            Throwable error = readAndAssertContent(totalContentBytesCount, originalContentString, buffers.length + 1, contentProducer);
            assertThat(error, is(expectedError));
        }
    }

    private Throwable readAndAssertContent(int totalContentBytesCount, String originalContentString, int totalContentCount, ContentProducer contentProducer)
    {
        int readBytes = 0;
        int nextContentCount = 0;
        String consumedString = "";
        Throwable error = null;
        while (true)
        {
            HttpInput.Content content = contentProducer.nextContent();
            nextContentCount++;

            if (content.isSpecial())
            {
                if (content.isEof())
                    break;
                error = content.getError();
                break;
            }

            byte[] b = new byte[content.remaining()];
            content.getByteBuffer().get(b);
            consumedString += new String(b, StandardCharsets.ISO_8859_1);

            readBytes += b.length;
        }
        assertThat(readBytes, is(totalContentBytesCount));
        assertThat(nextContentCount, is(totalContentCount));
        assertThat(consumedString, is(originalContentString));
        return error;
    }

    private static int countRemaining(ByteBuffer[] byteBuffers)
    {
        int total = 0;
        for (ByteBuffer byteBuffer : byteBuffers)
        {
            total += byteBuffer.remaining();
        }
        return total;
    }

    private static String asString(ByteBuffer[] buffers)
    {
        StringBuilder sb = new StringBuilder();
        for (ByteBuffer buffer : buffers)
        {
            byte[] b = new byte[buffer.remaining()];
            buffer.duplicate().get(b);
            sb.append(new String(b, StandardCharsets.ISO_8859_1));
        }
        return sb.toString();
    }

    private static ByteBuffer gzipByteBuffer(ByteBuffer uncompressedBuffer)
    {
        try
        {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            GZIPOutputStream output = new GZIPOutputStream(baos);

            byte[] b = new byte[uncompressedBuffer.remaining()];
            uncompressedBuffer.get(b);
            output.write(b);

            output.close();
            return ByteBuffer.wrap(baos.toByteArray());
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    private static class ContentListener
    {
        private ContentProducer contentProducer;

        private ContentListener()
        {
        }

        private void onContent()
        {
            try (AutoLock lock = contentProducer.lock())
            {
                contentProducer.onContentProducible();
            }
        }

        private void setContentProducer(ContentProducer contentProducer)
        {
            this.contentProducer = contentProducer;
        }
    }

    private static class ArrayDelayedHttpChannel extends HttpChannel
    {
        private final ByteBuffer[] byteBuffers;
        private final HttpInput.Content finalContent;
        private final ScheduledExecutorService scheduledExecutorService;
        private final ContentListener contentListener;
        private int counter;
        private volatile HttpInput.Content nextContent;

        public ArrayDelayedHttpChannel(ByteBuffer[] byteBuffers, HttpInput.Content finalContent, ScheduledExecutorService scheduledExecutorService, ContentListener contentListener)
        {
            super(new MockConnector(), new HttpConfiguration(), null, null);
            this.byteBuffers = new ByteBuffer[byteBuffers.length];
            this.finalContent = finalContent;
            this.scheduledExecutorService = scheduledExecutorService;
            this.contentListener = contentListener;
            for (int i = 0; i < byteBuffers.length; i++)
            {
                this.byteBuffers[i] = byteBuffers[i].duplicate();
            }
        }

        @Override
        public boolean needContent()
        {
            if (nextContent != null)
                return true;
            scheduledExecutorService.schedule(() ->
            {
                if (byteBuffers.length > counter)
                    nextContent = new HttpInput.Content(byteBuffers[counter++]);
                else
                    nextContent = finalContent;
                contentListener.onContent();
            }, 50, TimeUnit.MILLISECONDS);
            return false;
        }

        @Override
        public HttpInput.Content produceContent()
        {
            HttpInput.Content result = nextContent;
            nextContent = null;
            return result;
        }

        @Override
        public boolean failAllContent(Throwable failure)
        {
            nextContent = null;
            counter = byteBuffers.length;
            return false;
        }

        @Override
        public boolean failed(Throwable x)
        {
            return false;
        }

        @Override
        protected boolean eof()
        {
            return false;
        }
    }
}
