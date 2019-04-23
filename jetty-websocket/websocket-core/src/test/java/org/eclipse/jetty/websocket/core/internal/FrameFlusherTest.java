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
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.WebSocketConstants;
import org.eclipse.jetty.websocket.core.WebSocketWriteTimeoutException;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FrameFlusherTest
{
    public ByteBufferPool bufferPool = new MappedByteBufferPool();

    /**
     * Ensure post-close frames have their associated callbacks properly notified.
     */
    @Test
    public void testPostCloseFrameCallbacks() throws ExecutionException, InterruptedException, TimeoutException
    {
        Generator generator = new Generator(bufferPool);
        CapturingEndPoint endPoint = new CapturingEndPoint(bufferPool);
        int bufferSize = WebSocketConstants.DEFAULT_MAX_TEXT_MESSAGE_SIZE;
        int maxGather = 1;
        FrameFlusher frameFlusher = new FrameFlusher(bufferPool, generator, endPoint, bufferSize, maxGather);

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
                ()-> textFrameCallback.get(5, TimeUnit.SECONDS));
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
        Generator generator = new Generator(bufferPool);
        CapturingEndPoint endPoint = new CapturingEndPoint(bufferPool);
        int bufferSize = WebSocketConstants.DEFAULT_MAX_TEXT_MESSAGE_SIZE;
        int maxGather = 8;
        FrameFlusher frameFlusher = new FrameFlusher(bufferPool, generator, endPoint, bufferSize, maxGather);

        int largeMessageSize = 60000;
        byte[] buf = new byte[largeMessageSize];
        Arrays.fill(buf, (byte) 'x');
        String largeMessage = new String(buf, UTF_8);

        int messageCount = 10000;

        CompletableFuture<Void> serverTask = new CompletableFuture<>();

        CompletableFuture.runAsync(() -> {
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
        Generator generator = new Generator(bufferPool);
        BlockingEndpoint endPoint = new BlockingEndpoint(bufferPool);
        int bufferSize = WebSocketConstants.DEFAULT_MAX_TEXT_MESSAGE_SIZE;
        int maxGather = 8;


        CountDownLatch flusherFailure = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        FrameFlusher frameFlusher = new FrameFlusher(bufferPool, generator, endPoint, bufferSize, maxGather)
        {
            @Override
            public void onCompleteFailure(Throwable failure)
            {
                error.set(failure);
                flusherFailure.countDown();
                super.onCompleteFailure(failure);
            }
        };

        frameFlusher.setIdleTimeout(150);
        endPoint.setBlockTime(200);

        Frame frame = new Frame(OpCode.TEXT).setPayload("message").setFin(true);
        frameFlusher.enqueue(frame, Callback.NOOP, false);
        frameFlusher.iterate();

        assertTrue(flusherFailure.await(2, TimeUnit.SECONDS));
        assertThat(error.get(), instanceOf(WebSocketWriteTimeoutException.class));
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
                    if(frame != null)
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
        private static final Logger LOG = Log.getLogger(BlockingEndpoint.class);

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
