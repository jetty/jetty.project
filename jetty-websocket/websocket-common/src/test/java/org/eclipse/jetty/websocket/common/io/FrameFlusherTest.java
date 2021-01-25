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

package org.eclipse.jetty.websocket.common.io;

import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.WritePendingException;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.api.extensions.IncomingFrames;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.Generator;
import org.eclipse.jetty.websocket.common.Parser;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
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
        WebSocketPolicy policy = WebSocketPolicy.newServerPolicy();
        Generator generator = new Generator(policy, bufferPool);
        CapturingEndPoint endPoint = new CapturingEndPoint(WebSocketPolicy.newClientPolicy(), bufferPool);
        int bufferSize = policy.getMaxBinaryMessageBufferSize();
        int maxGather = 1;
        FrameFlusher frameFlusher = new FrameFlusher(bufferPool, generator, endPoint, bufferSize, maxGather);

        BatchMode batchMode = BatchMode.OFF;

        Frame closeFrame = new CloseInfo(StatusCode.MESSAGE_TOO_LARGE, "Message be to big").asFrame();
        Frame textFrame = new TextFrame().setPayload("Hello").setFin(true);

        FutureWriteCallback closeCallback = new FutureWriteCallback();
        FutureWriteCallback textFrameCallback = new FutureWriteCallback();

        assertTrue(frameFlusher.enqueue(closeFrame, closeCallback, batchMode));
        assertFalse(frameFlusher.enqueue(textFrame, textFrameCallback, batchMode));
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
        WebSocketPolicy policy = WebSocketPolicy.newServerPolicy();
        Generator generator = new Generator(policy, bufferPool);
        CapturingEndPoint endPoint = new CapturingEndPoint(WebSocketPolicy.newClientPolicy(), bufferPool);
        int bufferSize = policy.getMaxBinaryMessageBufferSize();
        int maxGather = 8;
        FrameFlusher frameFlusher = new FrameFlusher(bufferPool, generator, endPoint, bufferSize, maxGather);

        int largeMessageSize = 60000;
        byte[] buf = new byte[largeMessageSize];
        Arrays.fill(buf, (byte)'x');
        String largeMessage = new String(buf, UTF_8);

        int messageCount = 10000;
        BatchMode batchMode = BatchMode.OFF;

        CompletableFuture<Void> serverTask = new CompletableFuture<>();

        CompletableFuture.runAsync(() ->
        {
            // Run Server Task
            try
            {
                for (int i = 0; i < messageCount; i++)
                {
                    FutureWriteCallback callback = new FutureWriteCallback();
                    WebSocketFrame frame;

                    if (i % 2 == 0)
                    {
                        frame = new TextFrame().setPayload(largeMessage);
                    }
                    else
                    {
                        frame = new TextFrame().setPayload("Short Message: " + i);
                    }
                    frameFlusher.enqueue(frame, callback, batchMode);
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
        System.out.printf("Received: %,d frames%n", endPoint.incomingFrames.size());
    }

    public static class CapturingEndPoint extends MockEndPoint implements IncomingFrames
    {
        public Parser parser;
        public LinkedBlockingQueue<Frame> incomingFrames = new LinkedBlockingQueue<>();

        public CapturingEndPoint(WebSocketPolicy policy, ByteBufferPool bufferPool)
        {
            parser = new Parser(policy, bufferPool);
            parser.setIncomingFramesHandler(this);
        }

        @Override
        public void incomingFrame(Frame frame)
        {
            incomingFrames.offer(frame);
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
                    parser.parse(buffer);
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
}
