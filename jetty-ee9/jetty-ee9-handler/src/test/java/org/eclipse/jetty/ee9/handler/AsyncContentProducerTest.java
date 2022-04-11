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

package org.eclipse.jetty.ee9.handler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

import org.eclipse.jetty.ee9.handler.gzip.GzipHttpInputInterceptor;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.server.Content;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.component.Destroyable;
import org.eclipse.jetty.util.compression.InflaterPool;
import org.eclipse.jetty.util.thread.AutoLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

public class AsyncContentProducerTest
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
    public void testDestroyInterceptorOnRecycle()
    {
        DestroyableInterceptor interceptor = new DestroyableInterceptor();

        AsyncContentProducer contentProducer = new AsyncContentProducer(null);
        try (AutoLock lock = contentProducer.lock())
        {
            contentProducer.setInterceptor(interceptor);

            assertThat(interceptor.destroyed, is(false));
            contentProducer.recycle();
            assertThat(interceptor.destroyed, is(true));
        }
    }

    @Test
    public void testAsyncContentProducerNoInterceptor() throws Exception
    {
        ByteBuffer[] buffers = new ByteBuffer[3];
        buffers[0] = ByteBuffer.wrap("1 hello 1".getBytes(StandardCharsets.ISO_8859_1));
        buffers[1] = ByteBuffer.wrap("2 howdy 2".getBytes(StandardCharsets.ISO_8859_1));
        buffers[2] = ByteBuffer.wrap("3 hey ya 3".getBytes(StandardCharsets.ISO_8859_1));
        final int totalContentBytesCount = countRemaining(buffers);
        final String originalContentString = asString(buffers);

        CyclicBarrier barrier = new CyclicBarrier(2);

        ContentProducer contentProducer = new AsyncContentProducer(new ArrayDelayedHttpChannel(buffers, Content.EOF, scheduledExecutorService, barrier));

        try (AutoLock lock = contentProducer.lock())
        {
            Throwable error = readAndAssertContent(totalContentBytesCount, originalContentString, contentProducer, (buffers.length + 1) * 2, 0, 4, barrier);
            assertThat(error, nullValue());
        }
    }

    @Test
    public void testAsyncContentProducerNoInterceptorWithError() throws Exception
    {
        ByteBuffer[] buffers = new ByteBuffer[3];
        buffers[0] = ByteBuffer.wrap("1 hello 1".getBytes(StandardCharsets.ISO_8859_1));
        buffers[1] = ByteBuffer.wrap("2 howdy 2".getBytes(StandardCharsets.ISO_8859_1));
        buffers[2] = ByteBuffer.wrap("3 hey ya 3".getBytes(StandardCharsets.ISO_8859_1));
        final int totalContentBytesCount = countRemaining(buffers);
        final String originalContentString = asString(buffers);
        final Throwable expectedError = new EofException("Early EOF");

        CyclicBarrier barrier = new CyclicBarrier(2);

        ContentProducer contentProducer = new AsyncContentProducer(new ArrayDelayedHttpChannel(buffers, new Content.Error(expectedError), scheduledExecutorService, barrier));

        try (AutoLock lock = contentProducer.lock())
        {
            Throwable error = readAndAssertContent(totalContentBytesCount, originalContentString, contentProducer, (buffers.length + 1) * 2, 0, 4, barrier);
            assertThat(error, is(expectedError));
        }
    }

    @Test
    public void testAsyncContentProducerEofContentIsPassedToInterceptor() throws Exception
    {
        ByteBuffer[] buffers = new ByteBuffer[3];
        buffers[0] = ByteBuffer.wrap("1 hello 1".getBytes(StandardCharsets.ISO_8859_1));
        buffers[1] = ByteBuffer.wrap("2 howdy 2".getBytes(StandardCharsets.ISO_8859_1));
        buffers[2] = ByteBuffer.wrap("3 hey ya 3".getBytes(StandardCharsets.ISO_8859_1));
        final int totalContentBytesCount = countRemaining(buffers);
        final String originalContentString = asString(buffers);

        CyclicBarrier barrier = new CyclicBarrier(2);

        ContentProducer contentProducer = new AsyncContentProducer(new ArrayDelayedHttpChannel(buffers, Content.EOF, scheduledExecutorService, barrier));
        AccountingInterceptor interceptor = new AccountingInterceptor();
        try (AutoLock lock = contentProducer.lock())
        {
            contentProducer.setInterceptor(interceptor);

            Throwable error = readAndAssertContent(totalContentBytesCount, originalContentString, contentProducer, (buffers.length + 1) * 2, 0, 4, barrier);
            assertThat(error, nullValue());

            Content lastContent = contentProducer.nextContent();
            assertThat(lastContent.isSpecial(), is(true));
            assertThat(lastContent.isLast(), is(true));
        }

        assertThat(interceptor.contents.size(), is(4));
        assertThat(interceptor.contents.get(0).isSpecial(), is(false));
        assertThat(interceptor.contents.get(1).isSpecial(), is(false));
        assertThat(interceptor.contents.get(2).isSpecial(), is(false));
        assertThat(interceptor.contents.get(3).isSpecial(), is(true));
        assertThat(interceptor.contents.get(3).isLast(), is(true));
    }

    @Test
    public void testAsyncContentProducerErrorContentIsPassedToInterceptor() throws Exception
    {
        ByteBuffer[] buffers = new ByteBuffer[3];
        buffers[0] = ByteBuffer.wrap("1 hello 1".getBytes(StandardCharsets.ISO_8859_1));
        buffers[1] = ByteBuffer.wrap("2 howdy 2".getBytes(StandardCharsets.ISO_8859_1));
        buffers[2] = ByteBuffer.wrap("3 hey ya 3".getBytes(StandardCharsets.ISO_8859_1));
        final int totalContentBytesCount = countRemaining(buffers);
        final String originalContentString = asString(buffers);

        CyclicBarrier barrier = new CyclicBarrier(2);

        ContentProducer contentProducer = new AsyncContentProducer(new ArrayDelayedHttpChannel(buffers, new Content.Error(new Throwable("testAsyncContentProducerErrorContentIsPassedToInterceptor error")), scheduledExecutorService, barrier));
        AccountingInterceptor interceptor = new AccountingInterceptor();
        try (AutoLock lock = contentProducer.lock())
        {
            contentProducer.setInterceptor(interceptor);

            Throwable error = readAndAssertContent(totalContentBytesCount, originalContentString, contentProducer, (buffers.length + 1) * 2, 0, 4, barrier);
            assertThat(error.getMessage(), is("testAsyncContentProducerErrorContentIsPassedToInterceptor error"));

            Content lastContent = contentProducer.nextContent();
            assertThat(lastContent.isSpecial(), is(true));
            assertThat(Content.getError(lastContent).getMessage(), is("testAsyncContentProducerErrorContentIsPassedToInterceptor error"));
        }

        assertThat(interceptor.contents.size(), is(4));
        assertThat(interceptor.contents.get(0).isSpecial(), is(false));
        assertThat(interceptor.contents.get(1).isSpecial(), is(false));
        assertThat(interceptor.contents.get(2).isSpecial(), is(false));
        assertThat(interceptor.contents.get(3).isSpecial(), is(true));
        assertThat(Content.getError(interceptor.contents.get(3)).getMessage(), is("testAsyncContentProducerErrorContentIsPassedToInterceptor error"));
    }

    @Test
    public void testAsyncContentProducerInterceptorGeneratesError()
    {
        AtomicInteger contentReleasedCount = new AtomicInteger();
        ContentProducer contentProducer = new AsyncContentProducer(new ContentListHttpChannel(List.of(new Content.Buffer(ByteBuffer.allocate(1))
        {
            @Override
            public void release()
            {
                contentReleasedCount.incrementAndGet();
            }
        }), Content.EOF));
        try (AutoLock lock = contentProducer.lock())
        {
            contentProducer.setInterceptor(content -> new Content.Error(new Throwable("testAsyncContentProducerInterceptorGeneratesError interceptor error")));

            assertThat(contentProducer.isReady(), is(true));
            assertThat(contentProducer.isError(), is(true));

            Content content1 = contentProducer.nextContent();
            assertThat(content1.isSpecial(), is(true));
            assertThat(Content.getError(content1).getMessage(), is("testAsyncContentProducerInterceptorGeneratesError interceptor error"));

            Content content2 = contentProducer.nextContent();
            assertThat(content2.isSpecial(), is(true));
            assertThat(Content.getError(content2).getMessage(), is("testAsyncContentProducerInterceptorGeneratesError interceptor error"));
        }
        assertThat(contentReleasedCount.get(), is(1));
    }

    @Test
    public void testAsyncContentProducerInterceptorGeneratesEof()
    {
        AtomicInteger contentReleasedCount = new AtomicInteger();
        ContentProducer contentProducer = new AsyncContentProducer(new ContentListHttpChannel(List.of(new Content.Buffer(ByteBuffer.allocate(1))
        {
            @Override
            public void release()
            {
                contentReleasedCount.incrementAndGet();
            }
        }), new Content.Error(new Throwable("should not reach this"))));
        try (AutoLock lock = contentProducer.lock())
        {
            contentProducer.setInterceptor(content -> Content.EOF);

            assertThat(contentProducer.isReady(), is(true));
            assertThat(contentProducer.isError(), is(false));

            Content content1 = contentProducer.nextContent();
            assertThat(content1.isSpecial(), is(true));
            assertThat(content1.isLast(), is(true));

            Content content2 = contentProducer.nextContent();
            assertThat(content2.isSpecial(), is(true));
            assertThat(content2.isLast(), is(true));
        }
        assertThat(contentReleasedCount.get(), is(1));
    }

    @Test
    public void testAsyncContentProducerInterceptorThrows()
    {
        AtomicInteger contentReleasedCount = new AtomicInteger();
        ContentProducer contentProducer = new AsyncContentProducer(new ContentListHttpChannel(List.of(new Content.Buffer(ByteBuffer.allocate(1))
        {
            @Override
            public void release()
            {
                contentReleasedCount.incrementAndGet();
            }
        }), Content.EOF));
        try (AutoLock lock = contentProducer.lock())
        {
            contentProducer.setInterceptor(content ->
            {
                throw new RuntimeException("testAsyncContentProducerInterceptorThrows error");
            });

            assertThat(contentProducer.isReady(), is(true));
            assertThat(contentProducer.isError(), is(true));

            Content content1 = contentProducer.nextContent();
            assertThat(content1.isSpecial(), is(true));
            assertThat(Content.getError(content1).getCause().getMessage(), is("testAsyncContentProducerInterceptorThrows error"));

            Content content2 = contentProducer.nextContent();
            assertThat(content2.isSpecial(), is(true));
            assertThat(Content.getError(content2).getCause().getMessage(), is("testAsyncContentProducerInterceptorThrows error"));
        }
        assertThat(contentReleasedCount.get(), is(1));
    }

    @Test
    public void testAsyncContentProducerInterceptorDoesNotConsume()
    {
        AtomicInteger contentReleasedCount = new AtomicInteger();
        AtomicInteger interceptorContentReleasedCount = new AtomicInteger();
        ContentProducer contentProducer = new AsyncContentProducer(new ContentListHttpChannel(List.of(new Content.Buffer(ByteBuffer.allocate(1))
        {
            @Override
            public void release()
            {
                contentReleasedCount.incrementAndGet();
            }
        }), Content.EOF));
        try (AutoLock lock = contentProducer.lock())
        {
            contentProducer.setInterceptor(content -> new Content.Buffer(ByteBuffer.allocate(1))
            {
                @Override
                public void release()
                {
                    interceptorContentReleasedCount.incrementAndGet();
                }
            });

            assertThat(contentProducer.isReady(), is(true));

            Content content1 = contentProducer.nextContent();
            assertThat(content1.isSpecial(), is(true));
            assertThat(Content.getError(content1).getMessage(), endsWith("did not consume any of the 1 remaining byte(s) of content"));

            Content content2 = contentProducer.nextContent();
            assertThat(content2.isSpecial(), is(true));
            assertThat(Content.getError(content2).getMessage(), endsWith("did not consume any of the 1 remaining byte(s) of content"));
        }
        assertThat(contentReleasedCount.get(), is(1));
        assertThat(interceptorContentReleasedCount.get(), is(1));
    }

    @Test
    public void testAsyncContentProducerInterceptorDoesNotConsumeEmptyContent()
    {
        AtomicInteger contentReleasedCount = new AtomicInteger();
        AtomicInteger specialContentInterceptedCount = new AtomicInteger();
        AtomicInteger nullContentInterceptedCount = new AtomicInteger();
        ContentProducer contentProducer = new AsyncContentProducer(new ContentListHttpChannel(List.of(new Content.Buffer(ByteBuffer.allocate(0))
        {
            @Override
            public void release()
            {
                contentReleasedCount.incrementAndGet();
            }
        }), Content.EOF));
        try (AutoLock lock = contentProducer.lock())
        {
            contentProducer.setInterceptor(content ->
            {
                if (content.isSpecial())
                {
                    specialContentInterceptedCount.incrementAndGet();
                    return content;
                }
                nullContentInterceptedCount.incrementAndGet();
                return null;
            });

            Content content1 = contentProducer.nextContent();
            assertThat(content1.isSpecial(), is(true));
            assertThat(content1.isLast(), is(true));

            Content content2 = contentProducer.nextContent();
            assertThat(content2.isSpecial(), is(true));
            assertThat(content2.isLast(), is(true));
        }
        assertThat(contentReleasedCount.get(), is(1));
        assertThat(specialContentInterceptedCount.get(), is(1));
        assertThat(nullContentInterceptedCount.get(), is(1));
    }

    @Test
    public void testAsyncContentProducerGzipInterceptor() throws Exception
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

        CyclicBarrier barrier = new CyclicBarrier(2);

        ContentProducer contentProducer = new AsyncContentProducer(new ArrayDelayedHttpChannel(buffers, Content.EOF, scheduledExecutorService, barrier));
        try (AutoLock lock = contentProducer.lock())
        {
            contentProducer.setInterceptor(new GzipHttpInputInterceptor(inflaterPool, new ArrayByteBufferPool(1, 1, 2), 32));

            Throwable error = readAndAssertContent(totalContentBytesCount, originalContentString, contentProducer, (buffers.length + 1) * 2, 0, 4, barrier);
            assertThat(error, nullValue());
        }
    }

    @Test
    public void testAsyncContentProducerGzipInterceptorWithTinyBuffers() throws Exception
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

        CyclicBarrier barrier = new CyclicBarrier(2);

        ContentProducer contentProducer = new AsyncContentProducer(new ArrayDelayedHttpChannel(buffers, Content.EOF, scheduledExecutorService, barrier));
        try (AutoLock lock = contentProducer.lock())
        {
            contentProducer.setInterceptor(new GzipHttpInputInterceptor(inflaterPool, new ArrayByteBufferPool(1, 1, 2), 1));

            Throwable error = readAndAssertContent(totalContentBytesCount, originalContentString, contentProducer, totalContentBytesCount + buffers.length + 2, 25, 4, barrier);
            assertThat(error, nullValue());
        }
    }

    @Test
    public void testAsyncContentProducerGzipInterceptorWithError() throws Exception
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

        CyclicBarrier barrier = new CyclicBarrier(2);

        ContentProducer contentProducer = new AsyncContentProducer(new ArrayDelayedHttpChannel(buffers, new Content.Error(expectedError), scheduledExecutorService, barrier));
        try (AutoLock lock = contentProducer.lock())
        {
            contentProducer.setInterceptor(new GzipHttpInputInterceptor(inflaterPool, new ArrayByteBufferPool(1, 1, 2), 32));

            Throwable error = readAndAssertContent(totalContentBytesCount, originalContentString, contentProducer, (buffers.length + 1) * 2, 0, 4, barrier);
            assertThat(error, is(expectedError));
        }
    }

    private Throwable readAndAssertContent(int totalContentBytesCount, String originalContentString, ContentProducer contentProducer, int totalContentCount, int readyCount, int notReadyCount, CyclicBarrier barrier) throws InterruptedException, BrokenBarrierException, TimeoutException
    {
        int readBytes = 0;
        String consumedString = "";
        int nextContentCount = 0;
        int isReadyFalseCount = 0;
        int isReadyTrueCount = 0;
        Throwable error = null;

        while (true)
        {
            if (contentProducer.isReady())
                isReadyTrueCount++;
            else
                isReadyFalseCount++;

            Content content = contentProducer.nextContent();
            nextContentCount++;
            if (content == null)
            {
                barrier.await(5, TimeUnit.SECONDS);
                content = contentProducer.nextContent();
                nextContentCount++;
            }
            assertThat(content, notNullValue());

            if (content.isSpecial())
            {
                if (content.isLast())
                    break;
                error = Content.getError(content);
                break;
            }

            byte[] b = new byte[content.remaining()];
            readBytes += b.length;
            content.getByteBuffer().get(b);
            consumedString += new String(b, StandardCharsets.ISO_8859_1);
            content.getByteBuffer().position(content.getByteBuffer().position() + content.remaining());
        }

        assertThat(nextContentCount, is(totalContentCount));
        assertThat(readBytes, is(totalContentBytesCount));
        assertThat(consumedString, is(originalContentString));
        assertThat(isReadyFalseCount, is(notReadyCount));
        assertThat(isReadyTrueCount, is(readyCount));
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

    private static class ContentListHttpChannel extends HttpChannel
    {
        private final List<Content> contents;
        private final Content finalContent;
        private int index;

        public ContentListHttpChannel(List<Content> contents, Content finalContent)
        {
            super(null, null);
            this.contents = contents;
            this.finalContent = finalContent;
        }

        /*
        @Override
        public boolean needContent()
        {
            return true;
        }

        @Override
        public Content produceContent()
        {
            Content c;
            if (index < contents.size())
                c = contents.get(index++);
            else
                c = finalContent;
            return c;
        }

         */

        @Override
        public boolean failAllContent(Throwable failure)
        {
            return false;
        }

        @Override
        public boolean failed(Throwable failure)
        {
            return false;
        }

        @Override
        protected boolean eof()
        {
            return false;
        }
    }

    private static class ArrayDelayedHttpChannel extends HttpChannel
    {
        private final ByteBuffer[] byteBuffers;
        private final Content finalContent;
        private final ScheduledExecutorService scheduledExecutorService;
        private final CyclicBarrier barrier;
        private int counter;
        private volatile Content nextContent;

        public ArrayDelayedHttpChannel(ByteBuffer[] byteBuffers, Content finalContent, ScheduledExecutorService scheduledExecutorService, CyclicBarrier barrier)
        {
            super(new ContextHandler(new Server()), new MockConnectionMetaData());
            this.byteBuffers = new ByteBuffer[byteBuffers.length];
            this.finalContent = finalContent;
            this.scheduledExecutorService = scheduledExecutorService;
            this.barrier = barrier;
            for (int i = 0; i < byteBuffers.length; i++)
            {
                this.byteBuffers[i] = byteBuffers[i].duplicate();
            }
        }

        /*
        @Override
        public boolean needContent()
        {
            if (nextContent != null)
                return true;
            scheduledExecutorService.schedule(() ->
            {
                if (byteBuffers.length > counter)
                    nextContent = new Content.Buffer(byteBuffers[counter++]);
                else
                    nextContent = finalContent;
                try
                {
                    barrier.await(5, TimeUnit.SECONDS);
                }
                catch (Exception e)
                {
                    throw new AssertionError(e);
                }
            }, 50, TimeUnit.MILLISECONDS);
            return false;
        }

        @Override
        public Content produceContent()
        {
            Content result = nextContent;
            nextContent = null;
            return result;
        }

         */

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

    private static class DestroyableInterceptor implements Destroyable, HttpInput.Interceptor
    {
        private boolean destroyed = false;

        @Override
        public void destroy()
        {
            destroyed = true;
        }

        @Override
        public Content readFrom(Content content)
        {
            return null;
        }
    }

    private static class AccountingInterceptor implements HttpInput.Interceptor
    {
        private final List<Content> contents = new ArrayList<>();

        @Override
        public Content readFrom(Content content)
        {
            if (content.isSpecial() || !contents.contains(content))
                contents.add(content);
            return content;
        }
    }
}
