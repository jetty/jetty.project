//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.ByteBuffer;
import java.nio.channels.WritePendingException;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.api.extensions.IncomingFrames;
import org.eclipse.jetty.websocket.common.Generator;
import org.eclipse.jetty.websocket.common.Parser;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.junit.jupiter.api.Test;

public class FrameFlusherTest
{
    public ByteBufferPool bufferPool = new MappedByteBufferPool();

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
        SaneFrameOrderingEndPoint endPoint = new SaneFrameOrderingEndPoint(WebSocketPolicy.newClientPolicy(), bufferPool);
        int bufferSize = policy.getMaxBinaryMessageBufferSize();
        int maxGather = 8;
        FrameFlusher frameFlusher = new FrameFlusher(bufferPool, generator, endPoint, bufferSize, maxGather);

        int largeMessageSize = 60000;
        byte buf[] = new byte[largeMessageSize];
        Arrays.fill(buf, (byte) 'x');
        String largeMessage = new String(buf, UTF_8);

        int messageCount = 10000;
        BatchMode batchMode = BatchMode.OFF;

        CompletableFuture<Void> serverTask = new CompletableFuture<>();

        CompletableFuture.runAsync(() -> {
            // Run Server Task
            try
            {
                for (int i = 0; i < messageCount; i++)
                {
                    FutureWriteCallback callback = new FutureWriteCallback();
                    WebSocketFrame frame;

                    if (i % 2 == 0)
                        frame = new TextFrame().setPayload(largeMessage);
                    else
                        frame = new TextFrame().setPayload("Short Message: " + i);
                    frameFlusher.enqueue(frame, callback, batchMode);
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
        System.out.printf("Received: %,d frames / %,d errors%n", endPoint.incomingFrames, endPoint.incomingErrors);
    }

    public static class SaneFrameOrderingEndPoint extends MockEndPoint implements IncomingFrames
    {
        public Parser parser;
        public int incomingFrames;
        public int incomingErrors;

        public SaneFrameOrderingEndPoint(WebSocketPolicy policy, ByteBufferPool bufferPool)
        {
            parser = new Parser(policy, bufferPool);
            parser.setIncomingFramesHandler(this);
        }

        @Override
        public void incomingError(Throwable t)
        {
            incomingErrors++;
        }

        @Override
        public void incomingFrame(Frame frame)
        {
            incomingFrames++;
        }

        @Override
        public void shutdownOutput()
        {
            // ignore
        }

        @Override
        public void write(Callback callback, ByteBuffer... buffers) throws WritePendingException
        {
            try
            {
                for (ByteBuffer buffer : buffers)
                {
                    parser.parse(buffer);
                }
                if (callback != null)
                    callback.succeeded();
            }
            catch (WritePendingException e)
            {
                throw e;
            }
            catch (Throwable t)
            {
                if (callback != null)
                    callback.failed(t);
            }
        }
    }
}
