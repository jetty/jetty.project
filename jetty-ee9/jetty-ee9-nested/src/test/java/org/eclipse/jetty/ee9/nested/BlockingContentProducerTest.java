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

package org.eclipse.jetty.ee9.nested;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.eclipse.jetty.util.component.Destroyable;
import org.eclipse.jetty.util.thread.AutoLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

public class BlockingContentProducerTest
{
    private ScheduledExecutorService scheduledExecutorService;

    @BeforeEach
    public void setUp()
    {
        scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
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
        try (AutoLock ignored = contentProducer.lock())
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
        RetainableByteBuffer[] buffers = new RetainableByteBuffer[3];
        buffers[0] = RetainableByteBuffer.wrap("1 hello 1", StandardCharsets.ISO_8859_1);
        buffers[1] = RetainableByteBuffer.wrap("2 howdy 2", StandardCharsets.ISO_8859_1);
        buffers[2] = RetainableByteBuffer.wrap("3 hey ya 3", StandardCharsets.ISO_8859_1);
        final long totalContentBytesCount = countRemaining(buffers);
        final String originalContentString = asString(buffers);

        ContentListener contentListener = new ContentListener();
        ArrayDelayedHttpChannel httpChannel = new ArrayDelayedHttpChannel(buffers, new HttpInput.EofContent(), scheduledExecutorService, contentListener);
        ContentProducer contentProducer = new BlockingContentProducer(new AsyncContentProducer(httpChannel));
        contentListener.setContentProducer(contentProducer);

        try (AutoLock ignored = contentProducer.lock())
        {
            Throwable error = readAndAssertContent(totalContentBytesCount, originalContentString, buffers.length + 1, contentProducer);
            assertThat(error, nullValue());
        }
    }

    @Test
    public void testBlockingContentProducerNoInterceptorWithError()
    {
        RetainableByteBuffer[] buffers = new RetainableByteBuffer[3];
        buffers[0] = RetainableByteBuffer.wrap("1 hello 1", StandardCharsets.ISO_8859_1);
        buffers[1] = RetainableByteBuffer.wrap("2 howdy 2", StandardCharsets.ISO_8859_1);
        buffers[2] = RetainableByteBuffer.wrap("3 hey ya 3", StandardCharsets.ISO_8859_1);
        final long totalContentBytesCount = countRemaining(buffers);
        final String originalContentString = asString(buffers);
        final Throwable expectedError = new EofException("Early EOF");

        ContentListener contentListener = new ContentListener();
        ArrayDelayedHttpChannel httpChannel = new ArrayDelayedHttpChannel(buffers, new HttpInput.ErrorContent(expectedError), scheduledExecutorService, contentListener);
        ContentProducer contentProducer = new BlockingContentProducer(new AsyncContentProducer(httpChannel));
        contentListener.setContentProducer(contentProducer);

        try (AutoLock ignored = contentProducer.lock())
        {
            Throwable error = readAndAssertContent(totalContentBytesCount, originalContentString, buffers.length + 1, contentProducer);
            assertThat(error, is(expectedError));
        }
    }

    @Test
    public void testBlockingContentProducerEofContentIsPassedToInterceptor()
    {
        RetainableByteBuffer[] buffers = new RetainableByteBuffer[3];
        buffers[0] = RetainableByteBuffer.wrap("1 hello 1", StandardCharsets.ISO_8859_1);
        buffers[1] = RetainableByteBuffer.wrap("2 howdy 2", StandardCharsets.ISO_8859_1);
        buffers[2] = RetainableByteBuffer.wrap("3 hey ya 3", StandardCharsets.ISO_8859_1);
        final long totalContentBytesCount = countRemaining(buffers);
        final String originalContentString = asString(buffers);

        ContentListener contentListener = new ContentListener();
        ArrayDelayedHttpChannel httpChannel = new ArrayDelayedHttpChannel(buffers, new HttpInput.EofContent(), scheduledExecutorService, contentListener);
        ContentProducer contentProducer = new BlockingContentProducer(new AsyncContentProducer(httpChannel));
        contentListener.setContentProducer(contentProducer);
        AccountingInterceptor interceptor = new AccountingInterceptor();
        try (AutoLock ignored = contentProducer.lock())
        {
            contentProducer.setInterceptor(interceptor);

            Throwable error = readAndAssertContent(totalContentBytesCount, originalContentString, buffers.length + 1, contentProducer);
            assertThat(error, nullValue());

            HttpInput.Content lastContent = contentProducer.nextContent();
            assertThat(lastContent.isSpecial(), is(true));
            assertThat(lastContent.isEof(), is(true));
        }

        assertThat(interceptor.contents.size(), is(4));
        assertThat(interceptor.contents.get(0).isSpecial(), is(false));
        assertThat(interceptor.contents.get(1).isSpecial(), is(false));
        assertThat(interceptor.contents.get(2).isSpecial(), is(false));
        assertThat(interceptor.contents.get(3).isSpecial(), is(true));
        assertThat(interceptor.contents.get(3).isEof(), is(true));
    }

    @Test
    public void testBlockingContentProducerErrorContentIsPassedToInterceptor()
    {
        RetainableByteBuffer[] buffers = new RetainableByteBuffer[3];
        buffers[0] = RetainableByteBuffer.wrap("1 hello 1", StandardCharsets.ISO_8859_1);
        buffers[1] = RetainableByteBuffer.wrap("2 howdy 2", StandardCharsets.ISO_8859_1);
        buffers[2] = RetainableByteBuffer.wrap("3 hey ya 3", StandardCharsets.ISO_8859_1);
        final long totalContentBytesCount = countRemaining(buffers);
        final String originalContentString = asString(buffers);

        ContentListener contentListener = new ContentListener();
        ArrayDelayedHttpChannel httpChannel = new ArrayDelayedHttpChannel(buffers, new HttpInput.ErrorContent(new Throwable("testBlockingContentProducerErrorContentIsPassedToInterceptor error")), scheduledExecutorService, contentListener);
        ContentProducer contentProducer = new BlockingContentProducer(new AsyncContentProducer(httpChannel));
        contentListener.setContentProducer(contentProducer);
        AccountingInterceptor interceptor = new AccountingInterceptor();
        try (AutoLock ignored = contentProducer.lock())
        {
            contentProducer.setInterceptor(interceptor);

            Throwable error = readAndAssertContent(totalContentBytesCount, originalContentString, buffers.length + 1, contentProducer);
            assertThat(error.getMessage(), is("testBlockingContentProducerErrorContentIsPassedToInterceptor error"));

            HttpInput.Content lastContent = contentProducer.nextContent();
            assertThat(lastContent.isSpecial(), is(true));
            assertThat(lastContent.getError().getMessage(), is("testBlockingContentProducerErrorContentIsPassedToInterceptor error"));
        }

        assertThat(interceptor.contents.size(), is(4));
        assertThat(interceptor.contents.get(0).isSpecial(), is(false));
        assertThat(interceptor.contents.get(1).isSpecial(), is(false));
        assertThat(interceptor.contents.get(2).isSpecial(), is(false));
        assertThat(interceptor.contents.get(3).isSpecial(), is(true));
        assertThat(interceptor.contents.get(3).getError().getMessage(), is("testBlockingContentProducerErrorContentIsPassedToInterceptor error"));
    }

    @Test
    public void testBlockingContentProducerInterceptorGeneratesError()
    {
        AtomicInteger contentSucceededCount = new AtomicInteger();
        ContentProducer contentProducer = new BlockingContentProducer(new AsyncContentProducer(new StaticContentHttpChannel(new HttpInput.Content(RetainableByteBuffer.allocate(1, false))
        {
            @Override
            public void succeeded()
            {
                contentSucceededCount.incrementAndGet();
            }
        })));
        try (AutoLock ignored = contentProducer.lock())
        {
            contentProducer.setInterceptor(content -> new HttpInput.ErrorContent(new Throwable("testBlockingContentProducerInterceptorGeneratesError interceptor error")));

            HttpInput.Content content1 = contentProducer.nextContent();
            assertThat(content1.isSpecial(), is(true));
            assertThat(content1.getError().getMessage(), is("testBlockingContentProducerInterceptorGeneratesError interceptor error"));

            assertThat(contentProducer.isError(), is(true));

            HttpInput.Content content2 = contentProducer.nextContent();
            assertThat(content2.isSpecial(), is(true));
            assertThat(content2.getError().getMessage(), is("testBlockingContentProducerInterceptorGeneratesError interceptor error"));
        }
        assertThat(contentSucceededCount.get(), is(1));
    }

    @Test
    public void testBlockingContentProducerInterceptorGeneratesEof()
    {
        AtomicInteger contentSucceededCount = new AtomicInteger();
        ContentProducer contentProducer = new BlockingContentProducer(new AsyncContentProducer(new StaticContentHttpChannel(new HttpInput.Content(RetainableByteBuffer.allocate(1, false))
        {
            @Override
            public void succeeded()
            {
                contentSucceededCount.incrementAndGet();
            }
        })));
        try (AutoLock ignored = contentProducer.lock())
        {
            contentProducer.setInterceptor(content -> new HttpInput.EofContent());

            HttpInput.Content content1 = contentProducer.nextContent();
            assertThat(content1.isSpecial(), is(true));
            assertThat(content1.isEof(), is(true));

            assertThat(contentProducer.isError(), is(false));

            HttpInput.Content content2 = contentProducer.nextContent();
            assertThat(content2.isSpecial(), is(true));
            assertThat(content2.isEof(), is(true));
        }
        assertThat(contentSucceededCount.get(), is(1));
    }

    @Test
    public void testBlockingContentProducerInterceptorThrows()
    {
        AtomicInteger contentFailedCount = new AtomicInteger();
        ContentProducer contentProducer = new BlockingContentProducer(new AsyncContentProducer(new StaticContentHttpChannel(new HttpInput.Content(RetainableByteBuffer.allocate(1, false))
        {
            @Override
            public void failed(Throwable x)
            {
                contentFailedCount.incrementAndGet();
            }
        })));
        try (AutoLock ignored = contentProducer.lock())
        {
            contentProducer.setInterceptor(content ->
            {
                throw new RuntimeException("testBlockingContentProducerInterceptorThrows error");
            });

            HttpInput.Content content1 = contentProducer.nextContent();
            assertThat(content1.isSpecial(), is(true));
            assertThat(content1.getError().getCause().getMessage(), is("testBlockingContentProducerInterceptorThrows error"));

            assertThat(contentProducer.isError(), is(true));

            HttpInput.Content content2 = contentProducer.nextContent();
            assertThat(content2.isSpecial(), is(true));
            assertThat(content2.getError().getCause().getMessage(), is("testBlockingContentProducerInterceptorThrows error"));
        }
        assertThat(contentFailedCount.get(), is(1));
    }

    @Test
    public void testBlockingContentProducerInterceptorDoesNotConsumeEmptyContent()
    {
        AtomicInteger contentSucceededCount = new AtomicInteger();
        AtomicInteger specialContentInterceptedCount = new AtomicInteger();
        AtomicInteger nullContentInterceptedCount = new AtomicInteger();
        ContentProducer contentProducer = new BlockingContentProducer(new AsyncContentProducer(new StaticContentHttpChannel(new HttpInput.Content(RetainableByteBuffer.allocate(0, false))
        {
            @Override
            public void succeeded()
            {
                contentSucceededCount.incrementAndGet();
            }
        })));
        try (AutoLock ignored = contentProducer.lock())
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

            HttpInput.Content content1 = contentProducer.nextContent();
            assertThat(content1.isSpecial(), is(true));
            assertThat(content1.isEof(), is(true));

            HttpInput.Content content2 = contentProducer.nextContent();
            assertThat(content2.isSpecial(), is(true));
            assertThat(content2.isEof(), is(true));
        }
        assertThat(contentSucceededCount.get(), is(1));
        assertThat(specialContentInterceptedCount.get(), is(1));
        assertThat(nullContentInterceptedCount.get(), is(1));
    }

    private Throwable readAndAssertContent(long totalContentBytesCount, String originalContentString, int totalContentCount, ContentProducer contentProducer)
    {
        long readBytes = 0;
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

            try (RetainableByteBuffer buffer = content.acquire())
            {
                readBytes += buffer.remaining();
                consumedString += buffer.getString(StandardCharsets.ISO_8859_1);
            }
        }
        assertThat(readBytes, is(totalContentBytesCount));
        assertThat(nextContentCount, is(totalContentCount));
        assertThat(consumedString, is(originalContentString));
        return error;
    }

    private static long countRemaining(RetainableByteBuffer[] buffers)
    {
        long total = 0;
        for (RetainableByteBuffer buffer : buffers)
        {
            total += buffer.remaining();
        }
        return total;
    }

    private static String asString(RetainableByteBuffer[] buffers)
    {
        StringBuilder sb = new StringBuilder();
        for (RetainableByteBuffer buffer : buffers)
        {
            // Do not consume the buffer.
            sb.append(buffer.getString(0, StandardCharsets.ISO_8859_1));
        }
        return sb.toString();
    }

    private static class ContentListener
    {
        private ContentProducer contentProducer;

        private ContentListener()
        {
        }

        private void onContent()
        {
            try (AutoLock ignored = contentProducer.lock())
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
        private HttpInput.Content content;

        public StaticContentHttpChannel(HttpInput.Content content)
        {
            super(new ContextHandler(), new MockConnectionMetaData(new MockConnector()));
            this.content = content;
        }

        @Override
        public boolean needContent()
        {
            return content != null;
        }

        @Override
        public HttpInput.Content produceContent()
        {
            HttpInput.Content c = content;
            content = new HttpInput.EofContent();
            return c;
        }

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
        private final RetainableByteBuffer[] buffers;
        private final HttpInput.Content finalContent;
        private final ScheduledExecutorService scheduledExecutorService;
        private final ContentListener contentListener;
        private int counter;
        private volatile HttpInput.Content nextContent;

        public ArrayDelayedHttpChannel(RetainableByteBuffer[] buffers, HttpInput.Content finalContent, ScheduledExecutorService scheduledExecutorService, ContentListener contentListener)
        {
            super(new ContextHandler(), new MockConnectionMetaData(new MockConnector()));
            this.buffers = new RetainableByteBuffer[buffers.length];
            this.finalContent = finalContent;
            this.scheduledExecutorService = scheduledExecutorService;
            this.contentListener = contentListener;
            for (int i = 0; i < buffers.length; i++)
            {
                this.buffers[i] = buffers[i];
            }
        }

        @Override
        public boolean needContent()
        {
            if (nextContent != null)
                return true;
            scheduledExecutorService.schedule(() ->
            {
                if (buffers.length > counter)
                    nextContent = new HttpInput.Content(buffers[counter++]);
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
            counter = buffers.length;
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
        public HttpInput.Content readFrom(HttpInput.Content content)
        {
            return null;
        }
    }

    private static class AccountingInterceptor implements HttpInput.Interceptor
    {
        private final List<HttpInput.Content> contents = new ArrayList<>();

        @Override
        public HttpInput.Content readFrom(HttpInput.Content content)
        {
            if (!contents.contains(content))
                contents.add(content);
            return content;
        }
    }
}
