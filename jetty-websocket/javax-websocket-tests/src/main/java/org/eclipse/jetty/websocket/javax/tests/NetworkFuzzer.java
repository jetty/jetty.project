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

package org.eclipse.jetty.websocket.javax.tests;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.SharedBlockingCallback;
import org.eclipse.jetty.websocket.core.Behavior;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.eclipse.jetty.websocket.core.internal.Generator;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class NetworkFuzzer extends Fuzzer.Adapter implements Fuzzer, AutoCloseable
{
    private final LocalServer server;
    private final WebSocketCoreClient client;
    private final RawUpgradeRequest upgradeRequest;
    private final UnitGenerator generator;
    private final FrameCapture frameCapture;
    private SharedBlockingCallback sharedBlockingCallback = new SharedBlockingCallback();

    public NetworkFuzzer(LocalServer server) throws Exception
    {
        this(server, server.getWsUri());
    }

    public NetworkFuzzer(LocalServer server, URI wsURI) throws Exception
    {
        this(server, wsURI, null);
    }

    public NetworkFuzzer(LocalServer server, URI wsURI, Map<String, String> requestHeaders) throws Exception
    {
        super();
        this.server = server;
        this.client = new WebSocketCoreClient();
        this.upgradeRequest = new RawUpgradeRequest(client, wsURI);
        if (requestHeaders != null)
        {
            HttpFields fields = this.upgradeRequest.getHeaders();
            requestHeaders.forEach((name, value) ->
            {
                fields.remove(name);
                fields.put(name, value);
            });
        }
        this.client.start();
        this.generator = new UnitGenerator(Behavior.CLIENT);

        CompletableFuture<FrameHandler.CoreSession> futureHandler = this.client.connect(upgradeRequest);
        CompletableFuture<FrameCapture> futureCapture = futureHandler.thenCombine(upgradeRequest.getFuture(), (session, capture) -> capture);
        this.frameCapture = futureCapture.get(10, TimeUnit.SECONDS);
    }

    @SuppressWarnings("Duplicates")
    @Override
    public ByteBuffer asNetworkBuffer(List<Frame> frames)
    {
        int bufferLength = frames.stream().mapToInt((f) -> f.getPayloadLength() + Generator.MAX_HEADER_LENGTH).sum();
        ByteBuffer buffer = ByteBuffer.allocate(bufferLength);
        for (Frame f : frames)
        {
            generator.generate(buffer, f);
        }
        BufferUtil.flipToFlush(buffer, 0);
        return buffer;
    }

    @Override
    public void close() throws Exception
    {
        this.client.stop();
    }

    @Override
    public void eof()
    {
        // Does nothing in NetworkFuzzer.
    }

    @Override
    public void expect(List<Frame> expected) throws InterruptedException
    {
        // TODO Wait for server to close?
        // frameCapture.waitUntilClosed();

        // Get the server send echo bytes
        assertExpected(frameCapture.receivedFrames, expected);
    }

    @Override
    public BlockingQueue<Frame> getOutputFrames()
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
    public void sendBulk(List<Frame> frames) throws IOException
    {
        frameCapture.writeRaw(asNetworkBuffer(frames));
    }

    @Override
    public void sendFrames(List<Frame> frames) throws IOException
    {
        for (Frame f : frames)
        {
            try (SharedBlockingCallback.Blocker blocker = sharedBlockingCallback.acquire())
            {
                frameCapture.coreSession.sendFrame(f, blocker, false);
            }
        }
    }

    @Override
    public void sendFrames(Frame... frames) throws IOException
    {
        for (Frame f : frames)
        {
            try (SharedBlockingCallback.Blocker blocker = sharedBlockingCallback.acquire())
            {
                frameCapture.coreSession.sendFrame(f, blocker, false);
            }
        }
    }

    @Override
    public void sendSegmented(List<Frame> frames, int segmentSize) throws IOException
    {
        ByteBuffer buffer = asNetworkBuffer(frames);

        while (buffer.remaining() > 0)
        {
            send(buffer, segmentSize);
        }
    }

    public static class RawUpgradeRequest extends ClientUpgradeRequest
    {
        private final FrameCapture frameCapture = new FrameCapture();
        private final CompletableFuture<FrameCapture> futureCapture;

        public RawUpgradeRequest(WebSocketCoreClient webSocketClient, URI requestURI)
        {
            super(webSocketClient, requestURI);
            this.futureCapture = new CompletableFuture<>();
        }

        public CompletableFuture<FrameCapture> getFuture()
        {
            return futureCapture;
        }

        @Override
        public FrameHandler getFrameHandler()
        {
            return frameCapture;
        }

        @Override
        protected void customize(EndPoint endPoint)
        {
            frameCapture.setEndPoint(endPoint);
            futureCapture.complete(frameCapture);
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
        private final BlockingQueue<Frame> receivedFrames = new LinkedBlockingQueue<>();
        private EndPoint endPoint;
        private CountDownLatch openLatch = new CountDownLatch(1);
        private final SharedBlockingCallback blockingCallback = new SharedBlockingCallback();
        private CoreSession coreSession;

        public void setEndPoint(EndPoint endpoint)
        {
            this.endPoint = endpoint;
        }

        @Override
        public void onOpen(CoreSession coreSession, Callback callback)
        {
            this.coreSession = coreSession;
            this.openLatch.countDown();
            callback.succeeded();
        }

        @Override
        public void onFrame(Frame frame, Callback callback)
        {
            receivedFrames.offer(Frame.copy(frame));
            callback.succeeded();
        }

        @Override
        public void onError(Throwable cause, Callback callback)
        {
            callback.succeeded();
        }

        @Override
        public void onClosed(CloseStatus closeStatus, Callback callback)
        {
            callback.succeeded();
        }

        public void writeRaw(ByteBuffer buffer) throws IOException
        {
            try
            {
                assertTrue(openLatch.await(1, TimeUnit.SECONDS));
            }
            catch (InterruptedException e)
            {
                throw new IOException(e);
            }

            synchronized (this)
            {
                try (SharedBlockingCallback.Blocker blocker = blockingCallback.acquire())
                {
                    endPoint.write(blocker, buffer);
                }
            }
        }
    }
}
