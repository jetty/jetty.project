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

package org.eclipse.jetty.websocket.core.internal;

import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.WritePendingException;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.WebSocketConstants;
import org.eclipse.jetty.websocket.core.exception.WebSocketWriteTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FrameFlusherTest
{
    private final ByteBufferPool bufferPool = new MappedByteBufferPool();
    private Scheduler scheduler;

    @BeforeEach
    public void start() throws Exception
    {
        scheduler = new ScheduledExecutorScheduler();
        scheduler.start();
    }

    @AfterEach
    public void stop() throws Exception
    {
        scheduler.stop();
    }

    /**
     * Ensure post-close frames have their associated callbacks properly notified.
     */
    @Test
    public void testPostCloseFrameCallbacks() throws ExecutionException, InterruptedException, TimeoutException
    {
        Generator generator = new Generator();
        CapturingEndPoint endPoint = new CapturingEndPoint(bufferPool);
        int bufferSize = WebSocketConstants.DEFAULT_MAX_TEXT_MESSAGE_SIZE;
        int maxGather = 1;
        FrameFlusher frameFlusher = new FrameFlusher(bufferPool, scheduler, generator, endPoint, bufferSize, maxGather);

        Frame closeFrame = new Frame(OpCode.CLOSE).setPayload(CloseStatus.asPayloadBuffer(CloseStatus.MESSAGE_TOO_LARGE, "Message be to big"));
        Frame textFrame = new Frame(OpCode.TEXT).setPayload("Hello").setFin(true);

        FutureCallback closeCallback = new FutureCallback();
        FutureCallback textFrameCallback = new FutureCallback();

        assertTrue(frameFlusher.enqueue(closeFrame, closeCallback, false));
        assertFalse(frameFlusher.enqueue(textFrame, textFrameCallback, false));
        frameFlusher.iterate();

        closeCallback.get(5, TimeUnit.SECONDS);
        // If this throws a TimeoutException then the callback wasn't called.
        ExecutionException x = assertThrows(ExecutionException.class,
            () -> textFrameCallback.get(5, TimeUnit.SECONDS));
        assertThat(x.getCause(), instanceOf(ClosedChannelException.class));
    }

    /**
     * Ensure that FrameFlusher honors the correct order of websocket frames.
     *
     * @see <a href="https://github.com/eclipse/jetty.project/issues/2491">eclipse/jetty.project#2491</a>
     */
    @Test
    public void testLargeSmallText() throws ExecutionException, InterruptedException
    {
        Generator generator = new Generator();
        CapturingEndPoint endPoint = new CapturingEndPoint(bufferPool);
        int bufferSize = WebSocketConstants.DEFAULT_MAX_TEXT_MESSAGE_SIZE;
        int maxGather = 8;
        FrameFlusher frameFlusher = new FrameFlusher(bufferPool, scheduler, generator, endPoint, bufferSize, maxGather);

        int largeMessageSize = 60000;
        byte[] buf = new byte[largeMessageSize];
        Arrays.fill(buf, (byte)'x');
        String largeMessage = new String(buf, UTF_8);

        int messageCount = 10000;

        CompletableFuture<Void> serverTask = new CompletableFuture<>();

        CompletableFuture.runAsync(() ->
        {
            // Run Server Task
            try
            {
                for (int i = 0; i < messageCount; i++)
                {
                    FutureCallback callback = new FutureCallback();
                    Frame frame;

                    if (i % 2 == 0)
                    {
                        frame = new Frame(OpCode.TEXT).setPayload(largeMessage).setFin(true);
                    }
                    else
                    {
                        frame = new Frame(OpCode.TEXT).setPayload("Short Message: " + i).setFin(true);
                    }
                    frameFlusher.enqueue(frame, callback, false);
                    frameFlusher.iterate();
                    callback.get();
                }
            }
            catch (Throwable t)
            {
                serverTask.completeExceptionally(t);
            }
            serverTask.complete(null);
        });

        serverTask.get();
    }

    @Test
    public void testWriteTimeout() throws Exception
    {
        Generator generator = new Generator();
        BlockingEndpoint endPoint = new BlockingEndpoint(bufferPool);
        int bufferSize = WebSocketConstants.DEFAULT_MAX_TEXT_MESSAGE_SIZE;
        int maxGather = 8;

        CountDownLatch flusherFailure = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        FrameFlusher frameFlusher = new FrameFlusher(bufferPool, scheduler, generator, endPoint, bufferSize, maxGather)
        {
            @Override
            public void onCompleteFailure(Throwable failure)
            {
                error.set(failure);
                flusherFailure.countDown();
                super.onCompleteFailure(failure);
            }
        };

        frameFlusher.setIdleTimeout(100);
        endPoint.setBlockTime(200);

        Frame frame = new Frame(OpCode.TEXT).setPayload("message").setFin(true);
        frameFlusher.enqueue(frame, Callback.NOOP, false);
        frameFlusher.iterate();

        assertTrue(flusherFailure.await(2, TimeUnit.SECONDS));
        assertThat(error.get(), instanceOf(WebSocketWriteTimeoutException.class));
    }

    @Test
    public void testErrorClose() throws Exception
    {
        Generator generator = new Generator();
        BlockingEndpoint endPoint = new BlockingEndpoint(bufferPool);
        endPoint.setBlockTime(100);
        int bufferSize = WebSocketConstants.DEFAULT_MAX_TEXT_MESSAGE_SIZE;
        int maxGather = 8;
        FrameFlusher frameFlusher = new FrameFlusher(bufferPool, scheduler, generator, endPoint, bufferSize, maxGather);

        // Enqueue message before the error close.
        Frame frame1 = new Frame(OpCode.TEXT).setPayload("message before close").setFin(true);
        CountDownLatch failedFrame1 = new CountDownLatch(1);
        Callback callbackFrame1 = Callback.from(() ->
        {
        }, t -> failedFrame1.countDown());
        assertTrue(frameFlusher.enqueue(frame1, callbackFrame1, false));

        // Enqueue the close frame which should fail the previous frame as it is still in the queue.
        Frame closeFrame = new CloseStatus(CloseStatus.MESSAGE_TOO_LARGE).toFrame();
        CountDownLatch succeededCloseFrame = new CountDownLatch(1);
        Callback closeFrameCallback = Callback.from(succeededCloseFrame::countDown, t ->
        {
        });
        assertTrue(frameFlusher.enqueue(closeFrame, closeFrameCallback, false));
        assertTrue(failedFrame1.await(1, TimeUnit.SECONDS));

        // Any frames enqueued after this should fail.
        Frame frame2 = new Frame(OpCode.TEXT).setPayload("message after close").setFin(true);
        CountDownLatch failedFrame2 = new CountDownLatch(1);
        Callback callbackFrame2 = Callback.from(() ->
        {
        }, t -> failedFrame2.countDown());
        assertFalse(frameFlusher.enqueue(frame2, callbackFrame2, false));
        assertTrue(failedFrame2.await(1, TimeUnit.SECONDS));

        // Iterating should succeed the close callback.
        frameFlusher.iterate();
        assertTrue(succeededCloseFrame.await(1, TimeUnit.SECONDS));
    }

    public static class CapturingEndPoint extends MockEndpoint
    {
        public Parser parser;
        public LinkedBlockingQueue<Frame> incomingFrames = new LinkedBlockingQueue<>();

        public CapturingEndPoint(ByteBufferPool bufferPool)
        {
            parser = new Parser(bufferPool);
        }

        @Override
        public void shutdownOutput()
        {
            // ignore
        }

        @Override
        public void write(Callback callback, ByteBuffer... buffers) throws WritePendingException
        {
            Objects.requireNonNull(callback);
            try
            {
                for (ByteBuffer buffer : buffers)
                {
                    Parser.ParsedFrame frame = parser.parse(buffer);
                    if (frame != null)
                    {
                        incomingFrames.offer(frame);
                    }
                }
                callback.succeeded();
            }
            catch (WritePendingException e)
            {
                throw e;
            }
            catch (Throwable t)
            {
                callback.failed(t);
            }
        }
    }

    public static class BlockingEndpoint extends CapturingEndPoint
    {
        private static final Logger LOG = LoggerFactory.getLogger(BlockingEndpoint.class);

        private long blockTime = 0;
        public CountDownLatch closeLatch = new CountDownLatch(1);
        public volatile Throwable error;

        public void setBlockTime(int time)
        {
            blockTime = time;
        }

        public BlockingEndpoint(ByteBufferPool bufferPool)
        {
            super(bufferPool);
        }

        @Override
        public void write(Callback callback, ByteBuffer... buffers) throws WritePendingException
        {
            try
            {
                Thread.sleep(blockTime);
                super.write(callback, buffers);
            }
            catch (InterruptedException e)
            {
                callback.failed(e);
            }
        }

        @Override
        public void close(Throwable cause)
        {
            //ignore
        }
    }
}
