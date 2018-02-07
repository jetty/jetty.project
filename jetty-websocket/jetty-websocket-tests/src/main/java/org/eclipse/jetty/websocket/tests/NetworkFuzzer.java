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

package org.eclipse.jetty.websocket.tests;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.HttpResponse;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.SharedBlockingCallback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.Generator;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClientUpgradeRequest;
import org.eclipse.jetty.websocket.core.frames.WebSocketFrame;
import org.eclipse.jetty.websocket.core.io.BatchMode;

public class NetworkFuzzer extends Fuzzer.Adapter implements Fuzzer, AutoCloseable
{
    private final static Logger LOG = Log.getLogger(NetworkFuzzer.class);

    private final LocalServer server;
    private final RawWebSocketClient rawClient;
    private final RawUpgradeRequest upgradeRequest;
    private final UnitGenerator generator;
    private final FrameCapture frameCapture;
    private SharedBlockingCallback sharedBlockingCallback = new SharedBlockingCallback();

    public NetworkFuzzer(LocalServer server) throws Exception
    {
        super();
        this.server = server;
        this.rawClient = new RawWebSocketClient();
        CompletableFuture<FrameCapture> futureOnCapture = new CompletableFuture<>();
        this.upgradeRequest = new RawUpgradeRequest(rawClient, server.getWsUri(), futureOnCapture);
        this.rawClient.start();
        this.generator = new UnitGenerator(rawClient.getPolicy());

        CompletableFuture<FrameHandler.Channel> futureHandler = this.rawClient.connect(upgradeRequest);
        CompletableFuture<FrameCapture> futureCapture = futureHandler.thenCombine(futureOnCapture, (channel, capture) -> capture);
        this.frameCapture = futureCapture.get(10, TimeUnit.SECONDS);
    }

    @Override
    public ByteBuffer asNetworkBuffer(List<WebSocketFrame> frames)
    {
        int bufferLength = frames.stream().mapToInt((f) -> f.getPayloadLength() + Generator.MAX_HEADER_LENGTH).sum();
        ByteBuffer buffer = ByteBuffer.allocate(bufferLength);
        for (WebSocketFrame f : frames)
        {
            generator.generate(buffer, f);
        }
        BufferUtil.flipToFlush(buffer, 0);
        return buffer;
    }

    @Override
    public void close() throws Exception
    {
        this.rawClient.stop();
    }

    @Override
    public void eof()
    {
        // Does nothing in NetworkFuzzer.
    }

    @Override
    public void expect(List<WebSocketFrame> expected) throws InterruptedException
    {
        // Wait for server to close?
        frameCapture.waitUntilClosed();

        // Get the server send echo bytes
        assertExpected(frameCapture.receivedFrames, expected);
    }

    @Override
    public BlockingQueue<WebSocketFrame> getOutputFrames()
    {
        return frameCapture.receivedFrames;
    }

    @Override
    public void send(ByteBuffer buffer) throws IOException
    {
        frameCapture.writeRaw(buffer);
    }

    @Override
    public void send(ByteBuffer buffer, int length) throws IOException
    {
        int limit = Math.min(length, buffer.remaining());
        ByteBuffer sliced = buffer.slice();
        sliced.limit(limit);
        frameCapture.writeRaw(sliced);
        buffer.position(buffer.position() + limit);
    }

    @Override
    public void sendBulk(List<WebSocketFrame> frames) throws IOException
    {
        frameCapture.writeRaw(asNetworkBuffer(frames));
    }

    @Override
    public void sendFrames(List<WebSocketFrame> frames) throws IOException
    {
        for (WebSocketFrame f : frames)
        {
            try (SharedBlockingCallback.Blocker blocker = sharedBlockingCallback.acquire())
            {
                frameCapture.channel.sendFrame(f, blocker, BatchMode.OFF);
            }
        }
    }

    @Override
    public void sendFrames(WebSocketFrame... frames) throws IOException
    {
        for (WebSocketFrame f : frames)
        {
            try (SharedBlockingCallback.Blocker blocker = sharedBlockingCallback.acquire())
            {
                frameCapture.channel.sendFrame(f, blocker, BatchMode.OFF);
            }
        }
    }

    @Override
    public void sendSegmented(List<WebSocketFrame> frames, int segmentSize) throws IOException
    {
        ByteBuffer buffer = asNetworkBuffer(frames);

        while (buffer.remaining() > 0)
        {
            send(buffer, segmentSize);
        }
    }

    public static class RawWebSocketClient extends WebSocketCoreClient
    {
        public RawWebSocketClient()
        {
            super();
            getHttpClient().setName("RawWebSocketClient");
        }
    }

    public static class RawUpgradeRequest extends WebSocketCoreClientUpgradeRequest
    {
        private final CompletableFuture<FrameCapture> futureCapture;
        private EndPoint endPoint;

        public RawUpgradeRequest(WebSocketCoreClient webSocketClient, URI requestURI, CompletableFuture<FrameCapture> futureCapture)
        {
            super(webSocketClient, requestURI);
            this.futureCapture = futureCapture;
        }

        @Override
        public FrameHandler getFrameHandler(WebSocketCoreClient coreClient, WebSocketPolicy upgradePolicy, HttpResponse response)
        {
            FrameCapture frameCapture = new FrameCapture(this.endPoint);
            futureCapture.complete(frameCapture);
            return frameCapture;
        }

        @Override
        protected void customize(EndPoint endp)
        {
            this.endPoint = endp;
        }

        @Override
        protected void handleException(Throwable failure)
        {
            futureCapture.completeExceptionally(failure);
            super.handleException(failure);
        }
    }

    public static class FrameCapture implements FrameHandler
    {
        private final BlockingQueue<WebSocketFrame> receivedFrames = new LinkedBlockingQueue<>();
        private final EndPoint endPoint;
        private final SharedBlockingCallback blockingCallback = new SharedBlockingCallback();
        private final CountDownLatch closedLatch = new CountDownLatch(1);
        private Channel channel;

        public FrameCapture(EndPoint endPoint)
        {
            this.endPoint = endPoint;
        }

        @Override
        public void onClosed(CloseStatus closeStatus) throws Exception
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("onClosed(): {}", closeStatus);
            }
            // TODO: Fake the close frame (if WebSocketChannel doesn't)
            // receivedFrames.offer(new CloseFrame().setPayload(closeStatus));

            // Notify awaiting threads of closed state
            closedLatch.countDown();
        }

        @Override
        public void onError(Throwable cause) throws Exception
        {
            LOG.warn("onError()", cause);
        }

        @Override
        public void onFrame(Frame frame, Callback callback) throws Exception
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("onFrame(): {} {}", frame, BufferUtil.toDetailString(frame.getPayload()));
            }

            receivedFrames.offer(WebSocketFrame.copy(frame));
            callback.succeeded();
        }

        @Override
        public void onOpen(Channel channel) throws Exception
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("onOpen(): {}", channel);
            }
            this.channel = channel;
        }

        public void waitUntilClosed()
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("waitUntilClosed()");
            }

            try
            {
                assertThat("Closed within a reasonable time", closedLatch.await(Defaults.CLOSE_EVENT_TIMEOUT_MS, TimeUnit.MILLISECONDS), is(true));
                if (LOG.isDebugEnabled())
                {
                    LOG.debug("waitUntilClosed() - complete");
                }
            }
            catch (InterruptedException e)
            {
                fail("Failed on wait for client onClosed()");
            }
        }

        public void writeRaw(ByteBuffer buffer) throws IOException
        {
            try (SharedBlockingCallback.Blocker blocker = blockingCallback.acquire())
            {
                this.endPoint.write(blocker, buffer);
            }
        }
    }
}
