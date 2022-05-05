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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.component.Destroyable;
import org.eclipse.jetty.util.thread.AutoLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@Disabled // TODO
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

        ContentProducer contentProducer = new AsyncContentProducer(new ArrayDelayedHttpChannel(buffers, Content.Chunk.EOF, null, barrier));

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

        ContentProducer contentProducer = new AsyncContentProducer(new ArrayDelayedHttpChannel(buffers, new Content.Chunk.Error(expectedError), null, barrier));

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

        ContentProducer contentProducer = new AsyncContentProducer(new ArrayDelayedHttpChannel(buffers, Content.Chunk.EOF, null, barrier));
        AccountingInterceptor interceptor = new AccountingInterceptor();
        try (AutoLock ignored = contentProducer.lock())
        {
            contentProducer.setInterceptor(interceptor);

            Throwable error = readAndAssertContent(totalContentBytesCount, originalContentString, contentProducer, (buffers.length + 1) * 2, 0, 4, barrier);
            assertThat(error, nullValue());

            Content.Chunk lastContent = contentProducer.nextContent();
            assertThat(lastContent.isTerminal(), is(true));
            assertThat(lastContent.isLast(), is(true));
        }

        assertThat(interceptor.contents.size(), is(4));
        assertThat(interceptor.contents.get(0).isTerminal(), is(false));
        assertThat(interceptor.contents.get(1).isTerminal(), is(false));
        assertThat(interceptor.contents.get(2).isTerminal(), is(false));
        assertThat(interceptor.contents.get(3).isTerminal(), is(true));
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

        ContentProducer contentProducer = new AsyncContentProducer(new ArrayDelayedHttpChannel(buffers, new Content.Chunk.Error(new Throwable("testAsyncContentProducerErrorContentIsPassedToInterceptor error")), null, barrier));
        AccountingInterceptor interceptor = new AccountingInterceptor();
        try (AutoLock ignored = contentProducer.lock())
        {
            contentProducer.setInterceptor(interceptor);

            Throwable error = readAndAssertContent(totalContentBytesCount, originalContentString, contentProducer, (buffers.length + 1) * 2, 0, 4, barrier);
            assertThat(error.getMessage(), is("testAsyncContentProducerErrorContentIsPassedToInterceptor error"));

            Content.Chunk lastContent = contentProducer.nextContent();
            assertThat(lastContent.isTerminal(), is(true));
            assertThat(getError(lastContent).getMessage(), is("testAsyncContentProducerErrorContentIsPassedToInterceptor error"));
        }

        assertThat(interceptor.contents.size(), is(4));
        assertThat(interceptor.contents.get(0).isTerminal(), is(false));
        assertThat(interceptor.contents.get(1).isTerminal(), is(false));
        assertThat(interceptor.contents.get(2).isTerminal(), is(false));
        assertThat(interceptor.contents.get(3).isTerminal(), is(true));
        assertThat(getError(interceptor.contents.get(3)).getMessage(), is("testAsyncContentProducerErrorContentIsPassedToInterceptor error"));
    }

    @Test
    public void testAsyncContentProducerInterceptorGeneratesError()
    {
        AtomicInteger contentReleasedCount = new AtomicInteger();
        ContentProducer contentProducer = new AsyncContentProducer(new ContentListHttpChannel(List.of(Content.Chunk.from(ByteBuffer.allocate(1), false, contentReleasedCount::incrementAndGet)), Content.Chunk.EOF));
        try (AutoLock ignored = contentProducer.lock())
        {
            contentProducer.setInterceptor(content -> new Content.Chunk.Error(new Throwable("testAsyncContentProducerInterceptorGeneratesError interceptor error")));

            assertThat(contentProducer.isReady(), is(true));
            assertThat(contentProducer.isError(), is(true));

            Content.Chunk content1 = contentProducer.nextContent();
            assertThat(content1.isTerminal(), is(true));
            assertThat(getError(content1).getMessage(), is("testAsyncContentProducerInterceptorGeneratesError interceptor error"));

            Content.Chunk content2 = contentProducer.nextContent();
            assertThat(content2.isTerminal(), is(true));
            assertThat(getError(content2).getMessage(), is("testAsyncContentProducerInterceptorGeneratesError interceptor error"));
        }
        assertThat(contentReleasedCount.get(), is(1));
    }

    @Test
    public void testAsyncContentProducerInterceptorGeneratesEof()
    {
        AtomicInteger contentReleasedCount = new AtomicInteger();
        ContentProducer contentProducer = new AsyncContentProducer(new ContentListHttpChannel(List.of(Content.Chunk.from(ByteBuffer.allocate(1), false, contentReleasedCount::incrementAndGet)), new Content.Chunk.Error(new Throwable("should not reach this"))));
        try (AutoLock ignored = contentProducer.lock())
        {
            contentProducer.setInterceptor(content -> Content.Chunk.EOF);

            assertThat(contentProducer.isReady(), is(true));
            assertThat(contentProducer.isError(), is(false));

            Content.Chunk content1 = contentProducer.nextContent();
            assertThat(content1.isTerminal(), is(true));
            assertThat(content1.isLast(), is(true));

            Content.Chunk content2 = contentProducer.nextContent();
            assertThat(content2.isTerminal(), is(true));
            assertThat(content2.isLast(), is(true));
        }
        assertThat(contentReleasedCount.get(), is(1));
    }

    @Test
    public void testAsyncContentProducerInterceptorThrows()
    {
        AtomicInteger contentReleasedCount = new AtomicInteger();
        ContentProducer contentProducer = new AsyncContentProducer(new ContentListHttpChannel(List.of(Content.Chunk.from(ByteBuffer.allocate(1), false, contentReleasedCount::incrementAndGet)), Content.Chunk.EOF));
        try (AutoLock ignored = contentProducer.lock())
        {
            contentProducer.setInterceptor(content ->
            {
                throw new RuntimeException("testAsyncContentProducerInterceptorThrows error");
            });

            assertThat(contentProducer.isReady(), is(true));
            assertThat(contentProducer.isError(), is(true));

            Content.Chunk content1 = contentProducer.nextContent();
            assertThat(content1.isTerminal(), is(true));
            assertThat(getError(content1).getCause().getMessage(), is("testAsyncContentProducerInterceptorThrows error"));

            Content.Chunk content2 = contentProducer.nextContent();
            assertThat(content2.isTerminal(), is(true));
            assertThat(getError(content2).getCause().getMessage(), is("testAsyncContentProducerInterceptorThrows error"));
        }
        assertThat(contentReleasedCount.get(), is(1));
    }

    @Test
    public void testAsyncContentProducerInterceptorDoesNotConsume()
    {
        AtomicInteger contentReleasedCount = new AtomicInteger();
        AtomicInteger interceptorContentReleasedCount = new AtomicInteger();
        ContentProducer contentProducer = new AsyncContentProducer(new ContentListHttpChannel(List.of(Content.Chunk.from(ByteBuffer.allocate(1), false, contentReleasedCount::incrementAndGet)), Content.Chunk.EOF));
        try (AutoLock ignored = contentProducer.lock())
        {
            contentProducer.setInterceptor(content -> Content.Chunk.from(ByteBuffer.allocate(1), false, interceptorContentReleasedCount::incrementAndGet));

            assertThat(contentProducer.isReady(), is(true));

            Content.Chunk content1 = contentProducer.nextContent();
            assertThat(content1.isTerminal(), is(true));
            assertThat(getError(content1).getMessage(), endsWith("did not consume any of the 1 remaining byte(s) of content"));

            Content.Chunk content2 = contentProducer.nextContent();
            assertThat(content2.isTerminal(), is(true));
            assertThat(getError(content2).getMessage(), endsWith("did not consume any of the 1 remaining byte(s) of content"));
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
        ContentProducer contentProducer = new AsyncContentProducer(new ContentListHttpChannel(List.of(Content.Chunk.from(ByteBuffer.allocate(0), false, contentReleasedCount::incrementAndGet)), Content.Chunk.EOF));
        try (AutoLock ignored = contentProducer.lock())
        {
            contentProducer.setInterceptor(content ->
            {
                if (content.isTerminal())
                {
                    specialContentInterceptedCount.incrementAndGet();
                    return content;
                }
                nullContentInterceptedCount.incrementAndGet();
                return null;
            });

            Content.Chunk content1 = contentProducer.nextContent();
            assertThat(content1.isTerminal(), is(true));
            assertThat(content1.isLast(), is(true));

            Content.Chunk content2 = contentProducer.nextContent();
            assertThat(content2.isTerminal(), is(true));
            assertThat(content2.isLast(), is(true));
        }
        assertThat(contentReleasedCount.get(), is(1));
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

            Content.Chunk content = contentProducer.nextContent();
            nextContentCount++;
            if (content == null)
            {
                barrier.await(5, TimeUnit.SECONDS);
                content = contentProducer.nextContent();
                nextContentCount++;
            }
            assertThat(content, notNullValue());

            if (content.isTerminal())
            {
                if (content.isLast())
                    break;
                error = getError(content);
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

    private Throwable getError(Content.Chunk chunk)
    {
        return chunk instanceof Content.Chunk.Error error ? error.getCause() : null;
    }

    private static class ContentListHttpChannel extends HttpChannel
    {
        private final List<Content.Chunk> contents;
        private final Content.Chunk finalContent;
        private int index;

        public ContentListHttpChannel(List<Content.Chunk> contents, Content.Chunk finalContent)
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
        private final Content.Chunk finalContent;
        private final ScheduledExecutorService scheduledExecutorService;
        private final CyclicBarrier barrier;
        private int counter;
        private volatile Content.Chunk nextContent;

        public ArrayDelayedHttpChannel(ByteBuffer[] byteBuffers, Content.Chunk finalContent, ScheduledExecutorService scheduledExecutorService, CyclicBarrier barrier)
        {
            super(new ContextHandler(), new MockConnectionMetaData(new MockConnector(new Server())));
            getContextHandler().setServer(getConnectionMetaData().getConnector().getServer());

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
        public Content.Chunk readFrom(Content.Chunk content)
        {
            return null;
        }
    }

    private static class AccountingInterceptor implements HttpInput.Interceptor
    {
        private final List<Content.Chunk> contents = new ArrayList<>();

        @Override
        public Content.Chunk readFrom(Content.Chunk content)
        {
            if (content.isTerminal() || !contents.contains(content))
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
