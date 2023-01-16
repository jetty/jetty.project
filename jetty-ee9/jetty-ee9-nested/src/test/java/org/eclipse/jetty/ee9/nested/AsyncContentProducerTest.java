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
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.component.Destroyable;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.Scheduler;
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
    private Server _server;
    private LocalConnector _connector;
    private ContextHandler _contextHandler;
    private TestHandler _testHandler;

    @BeforeEach
    public void setUp() throws Exception
    {
        _server = new Server();
        _connector = new LocalConnector(_server);
        _server.addConnector(_connector);
        _contextHandler = new ContextHandler();
        _server.setHandler(_contextHandler);
        _testHandler = new TestHandler();
        _contextHandler.setHandler(_testHandler);
        _server.start();
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        _server.stop();
    }

    @Test
    public void testDestroyInterceptorOnRecycle()
    {
        DestroyableInterceptor interceptor = new DestroyableInterceptor();

        AsyncContentProducer contentProducer = new AsyncContentProducer(null);
        try (AutoLock ignored = contentProducer.lock())
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

        ContentProducer contentProducer = new AsyncContentProducer(new ArrayDelayedHttpChannel(buffers, new HttpInput.EofContent(), _connector.getScheduler(), barrier));

        try (AutoLock ignored = contentProducer.lock())
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

        ContentProducer contentProducer = new AsyncContentProducer(new ArrayDelayedHttpChannel(buffers, new HttpInput.ErrorContent(expectedError), _connector.getScheduler(), barrier));

        try (AutoLock ignored = contentProducer.lock())
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

        ContentProducer contentProducer = new AsyncContentProducer(new ArrayDelayedHttpChannel(buffers, new HttpInput.EofContent(), _connector.getScheduler(), barrier));
        AccountingInterceptor interceptor = new AccountingInterceptor();
        try (AutoLock ignored = contentProducer.lock())
        {
            contentProducer.setInterceptor(interceptor);

            Throwable error = readAndAssertContent(totalContentBytesCount, originalContentString, contentProducer, (buffers.length + 1) * 2, 0, 4, barrier);
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
    public void testAsyncContentProducerErrorContentIsPassedToInterceptor() throws Exception
    {
        ByteBuffer[] buffers = new ByteBuffer[3];
        buffers[0] = ByteBuffer.wrap("1 hello 1".getBytes(StandardCharsets.ISO_8859_1));
        buffers[1] = ByteBuffer.wrap("2 howdy 2".getBytes(StandardCharsets.ISO_8859_1));
        buffers[2] = ByteBuffer.wrap("3 hey ya 3".getBytes(StandardCharsets.ISO_8859_1));
        final int totalContentBytesCount = countRemaining(buffers);
        final String originalContentString = asString(buffers);

        CyclicBarrier barrier = new CyclicBarrier(2);

        ContentProducer contentProducer = new AsyncContentProducer(new ArrayDelayedHttpChannel(buffers, new HttpInput.ErrorContent(new Throwable("testAsyncContentProducerErrorContentIsPassedToInterceptor error")), _connector.getScheduler(), barrier));
        AccountingInterceptor interceptor = new AccountingInterceptor();
        try (AutoLock ignored = contentProducer.lock())
        {
            contentProducer.setInterceptor(interceptor);

            Throwable error = readAndAssertContent(totalContentBytesCount, originalContentString, contentProducer, (buffers.length + 1) * 2, 0, 4, barrier);
            assertThat(error.getMessage(), is("testAsyncContentProducerErrorContentIsPassedToInterceptor error"));

            HttpInput.Content lastContent = contentProducer.nextContent();
            assertThat(lastContent.isSpecial(), is(true));
            assertThat(lastContent.getError().getMessage(), is("testAsyncContentProducerErrorContentIsPassedToInterceptor error"));
        }

        assertThat(interceptor.contents.size(), is(4));
        assertThat(interceptor.contents.get(0).isSpecial(), is(false));
        assertThat(interceptor.contents.get(1).isSpecial(), is(false));
        assertThat(interceptor.contents.get(2).isSpecial(), is(false));
        assertThat(interceptor.contents.get(3).isSpecial(), is(true));
        assertThat(interceptor.contents.get(3).getError().getMessage(), is("testAsyncContentProducerErrorContentIsPassedToInterceptor error"));
    }

    @Test
    public void testAsyncContentProducerInterceptorGeneratesError()
    {
        AtomicInteger contentSucceededCount = new AtomicInteger();
        ContentProducer contentProducer = new AsyncContentProducer(new ContentListHttpChannel(List.of(new HttpInput.Content(ByteBuffer.allocate(1))
        {
            @Override
            public void succeeded()
            {
                contentSucceededCount.incrementAndGet();
            }
        }), new HttpInput.EofContent()));
        try (AutoLock ignored = contentProducer.lock())
        {
            contentProducer.setInterceptor(content -> new HttpInput.ErrorContent(new Throwable("testAsyncContentProducerInterceptorGeneratesError interceptor error")));

            assertThat(contentProducer.isReady(), is(true));
            assertThat(contentProducer.isError(), is(true));

            HttpInput.Content content1 = contentProducer.nextContent();
            assertThat(content1.isSpecial(), is(true));
            assertThat(content1.getError().getMessage(), is("testAsyncContentProducerInterceptorGeneratesError interceptor error"));

            HttpInput.Content content2 = contentProducer.nextContent();
            assertThat(content2.isSpecial(), is(true));
            assertThat(content2.getError().getMessage(), is("testAsyncContentProducerInterceptorGeneratesError interceptor error"));
        }
        assertThat(contentSucceededCount.get(), is(1));
    }

    @Test
    public void testAsyncContentProducerInterceptorGeneratesEof()
    {
        AtomicInteger contentSucceededCount = new AtomicInteger();
        ContentProducer contentProducer = new AsyncContentProducer(new ContentListHttpChannel(List.of(new HttpInput.Content(ByteBuffer.allocate(1))
        {
            @Override
            public void succeeded()
            {
                contentSucceededCount.incrementAndGet();
            }
        }), new HttpInput.ErrorContent(new Throwable("should not reach this"))));
        try (AutoLock ignored = contentProducer.lock())
        {
            contentProducer.setInterceptor(content -> new HttpInput.EofContent());

            assertThat(contentProducer.isReady(), is(true));
            assertThat(contentProducer.isError(), is(false));

            HttpInput.Content content1 = contentProducer.nextContent();
            assertThat(content1.isSpecial(), is(true));
            assertThat(content1.isEof(), is(true));

            HttpInput.Content content2 = contentProducer.nextContent();
            assertThat(content2.isSpecial(), is(true));
            assertThat(content2.isEof(), is(true));
        }
        assertThat(contentSucceededCount.get(), is(1));
    }

    @Test
    public void testAsyncContentProducerInterceptorThrows()
    {
        AtomicInteger contentFailedCount = new AtomicInteger();
        ContentProducer contentProducer = new AsyncContentProducer(new ContentListHttpChannel(List.of(new HttpInput.Content(ByteBuffer.allocate(1))
        {
            @Override
            public void failed(Throwable x)
            {
                contentFailedCount.incrementAndGet();
            }
        }), new HttpInput.EofContent()));
        try (AutoLock ignored = contentProducer.lock())
        {
            contentProducer.setInterceptor(content ->
            {
                throw new RuntimeException("testAsyncContentProducerInterceptorThrows error");
            });

            assertThat(contentProducer.isReady(), is(true));
            assertThat(contentProducer.isError(), is(true));

            HttpInput.Content content1 = contentProducer.nextContent();
            assertThat(content1.isSpecial(), is(true));
            assertThat(content1.getError().getCause().getMessage(), is("testAsyncContentProducerInterceptorThrows error"));

            HttpInput.Content content2 = contentProducer.nextContent();
            assertThat(content2.isSpecial(), is(true));
            assertThat(content2.getError().getCause().getMessage(), is("testAsyncContentProducerInterceptorThrows error"));
        }
        assertThat(contentFailedCount.get(), is(1));
    }

    @Test
    public void testAsyncContentProducerInterceptorDoesNotConsume()
    {
        AtomicInteger contentFailedCount = new AtomicInteger();
        AtomicInteger interceptorContentFailedCount = new AtomicInteger();
        ContentProducer contentProducer = new AsyncContentProducer(new ContentListHttpChannel(List.of(new HttpInput.Content(ByteBuffer.allocate(1))
        {
            @Override
            public void failed(Throwable x)
            {
                contentFailedCount.incrementAndGet();
            }
        }), new HttpInput.EofContent()));
        try (AutoLock ignored = contentProducer.lock())
        {
            contentProducer.setInterceptor(content -> new HttpInput.Content(ByteBuffer.allocate(1))
            {
                @Override
                public void failed(Throwable x)
                {
                    interceptorContentFailedCount.incrementAndGet();
                }
            });

            assertThat(contentProducer.isReady(), is(true));

            HttpInput.Content content1 = contentProducer.nextContent();
            assertThat(content1.isSpecial(), is(true));
            assertThat(content1.getError().getMessage(), endsWith("did not consume any of the 1 remaining byte(s) of content"));

            HttpInput.Content content2 = contentProducer.nextContent();
            assertThat(content2.isSpecial(), is(true));
            assertThat(content2.getError().getMessage(), endsWith("did not consume any of the 1 remaining byte(s) of content"));
        }
        assertThat(contentFailedCount.get(), is(1));
        assertThat(interceptorContentFailedCount.get(), is(1));
    }

    @Test
    public void testAsyncContentProducerInterceptorDoesNotConsumeEmptyContent()
    {
        AtomicInteger contentSucceededCount = new AtomicInteger();
        AtomicInteger specialContentInterceptedCount = new AtomicInteger();
        AtomicInteger nullContentInterceptedCount = new AtomicInteger();
        ContentProducer contentProducer = new AsyncContentProducer(new ContentListHttpChannel(List.of(new HttpInput.Content(ByteBuffer.allocate(0))
        {
            @Override
            public void succeeded()
            {
                contentSucceededCount.incrementAndGet();
            }
        }), new HttpInput.EofContent()));
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

            HttpInput.Content content = contentProducer.nextContent();
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
                if (content.isEof())
                    break;
                error = content.getError();
                break;
            }

            byte[] b = new byte[content.remaining()];
            readBytes += b.length;
            content.getByteBuffer().get(b);
            consumedString += new String(b, StandardCharsets.ISO_8859_1);
            content.skip(content.remaining());
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

    private class ContentListHttpChannel extends HttpChannel
    {
        private final List<HttpInput.Content> contents;
        private final HttpInput.Content finalContent;
        private int index;

        public ContentListHttpChannel(List<HttpInput.Content> contents, HttpInput.Content finalContent)
        {
            super(_contextHandler, new MockConnectionMetaData(_connector));
            this.contents = contents;
            this.finalContent = finalContent;
        }

        @Override
        public boolean needContent()
        {
            return true;
        }

        @Override
        public HttpInput.Content produceContent()
        {
            HttpInput.Content c;
            if (index < contents.size())
                c = contents.get(index++);
            else
                c = finalContent;
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

    private class ArrayDelayedHttpChannel extends HttpChannel
    {
        private final ByteBuffer[] byteBuffers;
        private final HttpInput.Content finalContent;
        private final Scheduler scheduler;
        private final CyclicBarrier barrier;
        private int counter;
        private volatile HttpInput.Content nextContent;

        public ArrayDelayedHttpChannel(ByteBuffer[] byteBuffers, HttpInput.Content finalContent, Scheduler scheduler, CyclicBarrier barrier)
        {
            super(_contextHandler, new MockConnectionMetaData(_connector));
            getContextHandler().setServer(getConnectionMetaData().getConnector().getServer());

            this.byteBuffers = new ByteBuffer[byteBuffers.length];
            this.finalContent = finalContent;
            this.scheduler = scheduler;
            this.barrier = barrier;
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
            scheduler.schedule(() ->
            {
                if (byteBuffers.length > counter)
                    nextContent = new HttpInput.Content(byteBuffers[counter++]);
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
            if (content.isSpecial() || !contents.contains(content))
                contents.add(content);
            return content;
        }
    }

    private static class TestHandler extends AbstractHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
        }
    }
}
