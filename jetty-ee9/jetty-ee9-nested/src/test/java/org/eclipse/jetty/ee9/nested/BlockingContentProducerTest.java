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

package org.eclipse.jetty.ee9.nested;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.server.Content;
import org.eclipse.jetty.util.component.Destroyable;
import org.eclipse.jetty.util.compression.InflaterPool;
import org.eclipse.jetty.util.thread.AutoLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

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
    public void testDestroyInterceptorOnRecycle()
    {
        DestroyableInterceptor interceptor = new DestroyableInterceptor();

        BlockingContentProducer contentProducer = new BlockingContentProducer(new AsyncContentProducer(null));
        try (AutoLock lock = contentProducer.lock())
        {
            contentProducer.setInterceptor(interceptor);

            assertThat(interceptor.destroyed, is(false));
            contentProducer.recycle();
            assertThat(interceptor.destroyed, is(true));
        }
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
        ArrayDelayedHttpChannel httpChannel = new ArrayDelayedHttpChannel(buffers, Content.EOF, scheduledExecutorService, contentListener);
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
        ArrayDelayedHttpChannel httpChannel = new ArrayDelayedHttpChannel(buffers, new Content.Error(expectedError), scheduledExecutorService, contentListener);
        ContentProducer contentProducer = new BlockingContentProducer(new AsyncContentProducer(httpChannel));
        contentListener.setContentProducer(contentProducer);

        try (AutoLock lock = contentProducer.lock())
        {
            Throwable error = readAndAssertContent(totalContentBytesCount, originalContentString, buffers.length + 1, contentProducer);
            assertThat(error, is(expectedError));
        }
    }

    @Test
    public void testBlockingContentProducerEofContentIsPassedToInterceptor() throws Exception
    {
        ByteBuffer[] buffers = new ByteBuffer[3];
        buffers[0] = ByteBuffer.wrap("1 hello 1".getBytes(StandardCharsets.ISO_8859_1));
        buffers[1] = ByteBuffer.wrap("2 howdy 2".getBytes(StandardCharsets.ISO_8859_1));
        buffers[2] = ByteBuffer.wrap("3 hey ya 3".getBytes(StandardCharsets.ISO_8859_1));
        final int totalContentBytesCount = countRemaining(buffers);
        final String originalContentString = asString(buffers);

        ContentListener contentListener = new ContentListener();
        ArrayDelayedHttpChannel httpChannel = new ArrayDelayedHttpChannel(buffers, Content.EOF, scheduledExecutorService, contentListener);
        ContentProducer contentProducer = new BlockingContentProducer(new AsyncContentProducer(httpChannel));
        contentListener.setContentProducer(contentProducer);
        AccountingInterceptor interceptor = new AccountingInterceptor();
        try (AutoLock lock = contentProducer.lock())
        {
            contentProducer.setInterceptor(interceptor);

            Throwable error = readAndAssertContent(totalContentBytesCount, originalContentString, buffers.length + 1, contentProducer);
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
    public void testBlockingContentProducerErrorContentIsPassedToInterceptor() throws Exception
    {
        ByteBuffer[] buffers = new ByteBuffer[3];
        buffers[0] = ByteBuffer.wrap("1 hello 1".getBytes(StandardCharsets.ISO_8859_1));
        buffers[1] = ByteBuffer.wrap("2 howdy 2".getBytes(StandardCharsets.ISO_8859_1));
        buffers[2] = ByteBuffer.wrap("3 hey ya 3".getBytes(StandardCharsets.ISO_8859_1));
        final int totalContentBytesCount = countRemaining(buffers);
        final String originalContentString = asString(buffers);

        ContentListener contentListener = new ContentListener();
        ArrayDelayedHttpChannel httpChannel = new ArrayDelayedHttpChannel(buffers, new Content.Error(new Throwable("testBlockingContentProducerErrorContentIsPassedToInterceptor error")), scheduledExecutorService, contentListener);
        ContentProducer contentProducer = new BlockingContentProducer(new AsyncContentProducer(httpChannel));
        contentListener.setContentProducer(contentProducer);
        AccountingInterceptor interceptor = new AccountingInterceptor();
        try (AutoLock lock = contentProducer.lock())
        {
            contentProducer.setInterceptor(interceptor);

            Throwable error = readAndAssertContent(totalContentBytesCount, originalContentString, buffers.length + 1, contentProducer);
            assertThat(error.getMessage(), is("testBlockingContentProducerErrorContentIsPassedToInterceptor error"));

            Content lastContent = contentProducer.nextContent();
            assertThat(lastContent.isSpecial(), is(true));
            assertThat(Content.getError(lastContent).getMessage(), is("testBlockingContentProducerErrorContentIsPassedToInterceptor error"));
        }

        assertThat(interceptor.contents.size(), is(4));
        assertThat(interceptor.contents.get(0).isSpecial(), is(false));
        assertThat(interceptor.contents.get(1).isSpecial(), is(false));
        assertThat(interceptor.contents.get(2).isSpecial(), is(false));
        assertThat(interceptor.contents.get(3).isSpecial(), is(true));
        assertThat(Content.getError(interceptor.contents.get(3)).getMessage(), is("testBlockingContentProducerErrorContentIsPassedToInterceptor error"));
    }

    @Test
    public void testBlockingContentProducerInterceptorGeneratesError()
    {
        AtomicInteger contentSucceededCount = new AtomicInteger();
        ContentProducer contentProducer = new BlockingContentProducer(new AsyncContentProducer(new StaticContentHttpChannel(new Content.Buffer(ByteBuffer.allocate(1))
        {
            @Override
            public void release()
            {
                contentSucceededCount.incrementAndGet();
            }
        })));
        try (AutoLock lock = contentProducer.lock())
        {
            contentProducer.setInterceptor(content -> new Content.Error(new Throwable("testBlockingContentProducerInterceptorGeneratesError interceptor error")));

            Content content1 = contentProducer.nextContent();
            assertThat(content1.isSpecial(), is(true));
            assertThat(Content.getError(content1).getMessage(), is("testBlockingContentProducerInterceptorGeneratesError interceptor error"));

            assertThat(contentProducer.isError(), is(true));

            Content content2 = contentProducer.nextContent();
            assertThat(content2.isSpecial(), is(true));
            assertThat(Content.getError(content2).getMessage(), is("testBlockingContentProducerInterceptorGeneratesError interceptor error"));
        }
        assertThat(contentSucceededCount.get(), is(1));
    }

    @Test
    public void testBlockingContentProducerInterceptorGeneratesEof()
    {
        AtomicInteger contentSucceededCount = new AtomicInteger();
        ContentProducer contentProducer = new BlockingContentProducer(new AsyncContentProducer(new StaticContentHttpChannel(new Content.Buffer(ByteBuffer.allocate(1))
        {
            @Override
            public void release()
            {
                contentSucceededCount.incrementAndGet();
            }
        })));
        try (AutoLock lock = contentProducer.lock())
        {
            contentProducer.setInterceptor(content -> Content.EOF);

            Content content1 = contentProducer.nextContent();
            assertThat(content1.isSpecial(), is(true));
            assertThat(content1.isLast(), is(true));

            assertThat(contentProducer.isError(), is(false));

            Content content2 = contentProducer.nextContent();
            assertThat(content2.isSpecial(), is(true));
            assertThat(content2.isLast(), is(true));
        }
        assertThat(contentSucceededCount.get(), is(1));
    }

    @Test
    public void testBlockingContentProducerInterceptorThrows()
    {
        AtomicInteger contentFailedCount = new AtomicInteger();
        ContentProducer contentProducer = new BlockingContentProducer(new AsyncContentProducer(new StaticContentHttpChannel(new Content.Buffer(ByteBuffer.allocate(1))
        {
            @Override
            public void release()
            {
                contentFailedCount.incrementAndGet();
            }
        })));
        try (AutoLock lock = contentProducer.lock())
        {
            contentProducer.setInterceptor(content ->
            {
                throw new RuntimeException("testBlockingContentProducerInterceptorThrows error");
            });

            Content content1 = contentProducer.nextContent();
            assertThat(content1.isSpecial(), is(true));
            assertThat(Content.getError(content1).getCause().getMessage(), is("testBlockingContentProducerInterceptorThrows error"));

            assertThat(contentProducer.isError(), is(true));

            Content content2 = contentProducer.nextContent();
            assertThat(content2.isSpecial(), is(true));
            assertThat(Content.getError(content2).getCause().getMessage(), is("testBlockingContentProducerInterceptorThrows error"));
        }
        assertThat(contentFailedCount.get(), is(1));
    }

    @Test
    public void testBlockingContentProducerInterceptorDoesNotConsume()
    {
        AtomicInteger contentFailedCount = new AtomicInteger();
        ContentProducer contentProducer = new BlockingContentProducer(new AsyncContentProducer(new StaticContentHttpChannel(new Content.Buffer(ByteBuffer.allocate(1))
        {
            @Override
            public void release()
            {
                contentFailedCount.incrementAndGet();
            }
        })));
        try (AutoLock lock = contentProducer.lock())
        {
            contentProducer.setInterceptor(content -> null);

            Content content1 = contentProducer.nextContent();
            assertThat(content1.isSpecial(), is(true));
            assertThat(Content.getError(content1).getMessage(), endsWith("did not consume any of the 1 remaining byte(s) of content"));

            Content content2 = contentProducer.nextContent();
            assertThat(content2.isSpecial(), is(true));
            assertThat(Content.getError(content2).getMessage(), endsWith("did not consume any of the 1 remaining byte(s) of content"));
        }
        assertThat(contentFailedCount.get(), is(1));
    }

    @Test
    public void testBlockingContentProducerInterceptorDoesNotConsumeEmptyContent()
    {
        AtomicInteger contentSucceededCount = new AtomicInteger();
        AtomicInteger specialContentInterceptedCount = new AtomicInteger();
        AtomicInteger nullContentInterceptedCount = new AtomicInteger();
        ContentProducer contentProducer = new BlockingContentProducer(new AsyncContentProducer(new StaticContentHttpChannel(new Content.Buffer(ByteBuffer.allocate(0))
        {
            @Override
            public void release()
            {
                contentSucceededCount.incrementAndGet();
            }
        })));
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
        assertThat(contentSucceededCount.get(), is(1));
        assertThat(specialContentInterceptedCount.get(), is(1));
        assertThat(nullContentInterceptedCount.get(), is(1));
    }

    private Throwable readAndAssertContent(int totalContentBytesCount, String originalContentString, int totalContentCount, ContentProducer contentProducer)
    {
        int readBytes = 0;
        int nextContentCount = 0;
        String consumedString = "";
        Throwable error = null;
        while (true)
        {
            Content content = contentProducer.nextContent();
            nextContentCount++;

            if (content.isSpecial())
            {
                if (content.isLast())
                    break;
                error = Content.getError(content);
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

    private static class StaticContentHttpChannel extends HttpChannel
    {
        private Content content;

        public StaticContentHttpChannel(Content content)
        {
            super(null, null);
            this.content = content;
        }

        /*
        @Override
        public boolean needContent()
        {
            return content != null;
        }

        @Override
        public Content produceContent()
        {
            Content c = content;
            content = Content.EOF;
            return c;
        }

        @Override
        public boolean failAllContent(Throwable failure)
        {
            return false;
        }

         */

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
        private final ContentListener contentListener;
        private int counter;
        private volatile Content nextContent;

        public ArrayDelayedHttpChannel(ByteBuffer[] byteBuffers, Content finalContent, ScheduledExecutorService scheduledExecutorService, ContentListener contentListener)
        {
            super(null, null);
            this.byteBuffers = new ByteBuffer[byteBuffers.length];
            this.finalContent = finalContent;
            this.scheduledExecutorService = scheduledExecutorService;
            this.contentListener = contentListener;
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
                contentListener.onContent();
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


        @Override
        public boolean failAllContent(Throwable failure)
        {
            nextContent = null;
            counter = byteBuffers.length;
            return false;
        }
         */

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
        private List<Content> contents = new ArrayList<>();

        @Override
        public Content readFrom(Content content)
        {
            if (!contents.contains(content))
                contents.add(content);
            return content;
        }
    }
}
