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

package org.eclipse.jetty.ee10.websocket.jakarta.tests;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.toolchain.test.ByteBufferAssert;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.websocket.core.Behavior;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.client.CoreClientUpgradeRequest;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.eclipse.jetty.websocket.core.internal.Generator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WebSocketTester implements AutoCloseable
{
    private static final Logger LOG = LoggerFactory.getLogger(WebSocketTester.class);

    private final WebSocketCoreClient client;
    private final UnitGenerator generator;
    private final FrameCapture frameCapture;

    public WebSocketTester(URI wsURI) throws Exception
    {
        this(wsURI, null);
    }

    public WebSocketTester(URI wsURI, Map<String, String> requestHeaders) throws Exception
    {
        this.client = new WebSocketCoreClient();
        RawUpgradeRequest upgradeRequest = new RawUpgradeRequest(client, wsURI);
        if (requestHeaders != null)
        {
            upgradeRequest.headers(fields ->
            {
                requestHeaders.forEach((name, value) ->
                {
                    fields.remove(name);
                    fields.put(name, value);
                });
            });
        }
        this.client.start();
        this.generator = new UnitGenerator(Behavior.CLIENT);

        CompletableFuture<CoreSession> futureHandler = this.client.connect(upgradeRequest);
        CompletableFuture<FrameCapture> futureCapture = futureHandler.thenCombine(upgradeRequest.getFuture(), (session, capture) -> capture);
        this.frameCapture = futureCapture.get(10, TimeUnit.SECONDS);
    }

    public ByteBuffer asNetworkBuffer(List<Frame> frames)
    {
        int bufferLength = frames.stream().mapToInt((f) -> f.getPayloadLength() + Generator.MAX_HEADER_LENGTH).sum();
        ByteBuffer buffer = BufferUtil.allocate(bufferLength);
        for (Frame f : frames)
        {
            generator.generate(buffer, f);
        }
        return buffer;
    }

    @Override
    public void close() throws Exception
    {
        this.client.stop();
    }

    /**
     * Assert that the provided expected WebSocketFrames are what was received
     * from the remote.
     *
     * @param expected the expected frames
     */
    public void expect(List<Frame> expected) throws InterruptedException
    {
        assertExpected(frameCapture.receivedFrames, expected);
    }

    public BlockingQueue<Frame> getOutputFrames()
    {
        return frameCapture.receivedFrames;
    }

    /**
     * Send raw bytes
     *
     * @param buffer the buffer
     */
    public void send(ByteBuffer buffer) throws IOException
    {
        frameCapture.writeRaw(buffer);
    }

    /**
     * Send some of the raw bytes
     *
     * @param buffer the buffer
     * @param length the number of bytes to send from buffer
     */
    public void send(ByteBuffer buffer, int length) throws IOException
    {
        int limit = Math.min(length, buffer.remaining());
        ByteBuffer sliced = buffer.slice();
        sliced.limit(limit);
        frameCapture.writeRaw(sliced);
        buffer.position(buffer.position() + limit);
    }

    /**
     * Generate a single ByteBuffer representing the entire
     * list of generated frames, and send it as a single
     * buffer
     *
     * @param frames the list of frames to send
     */
    public void sendBulk(List<Frame> frames) throws IOException
    {
        frameCapture.writeRaw(asNetworkBuffer(frames));
    }

    /**
     * Generate a ByteBuffer for each frame, and send each as
     * unique buffer containing each frame.
     *
     * @param frames the list of frames to send
     */
    public void sendFrames(List<Frame> frames) throws IOException
    {
        for (Frame f : frames)
        {
            FutureCallback callback = new FutureCallback();
            frameCapture.coreSession.sendFrame(f, callback, false);
            callback.block();
        }
    }

    /**
     * Generate a ByteBuffer for each frame, and send each as
     * unique buffer containing each frame.
     *
     * @param frames the list of frames to send
     */
    public void sendFrames(Frame... frames) throws IOException
    {
        sendFrames(Arrays.asList(frames));
    }

    /**
     * Generate a single ByteBuffer representing the entire list
     * of generated frames, and send segments of {@code segmentSize}
     * to remote as individual buffers.
     *
     * @param frames the list of frames to send
     * @param segmentSize the size of each segment to send
     */
    public void sendSegmented(List<Frame> frames, int segmentSize) throws IOException
    {
        ByteBuffer buffer = asNetworkBuffer(frames);

        while (buffer.remaining() > 0)
        {
            send(buffer, segmentSize);
        }
    }

    /**
     * Assert that the following frames contains the expected whole message.
     *
     * @param framesQueue the captured frames
     * @param expectedDataOp the expected message data type ({@link OpCode#BINARY} or {@link OpCode#TEXT})
     * @param expectedMessage the expected message
     */
    public void expectMessage(BlockingQueue<Frame> framesQueue, byte expectedDataOp, ByteBuffer expectedMessage) throws InterruptedException
    {
        ByteBuffer actualPayload = ByteBuffer.allocate(expectedMessage.remaining());

        try
        {
            Frame frame = framesQueue.poll(5, TimeUnit.SECONDS);
            assertThat("Initial Frame.opCode", frame.getOpCode(), is(expectedDataOp));

            actualPayload.put(frame.getPayload());
            while (!frame.isFin())
            {
                frame = framesQueue.poll(5, TimeUnit.SECONDS);
                assertThat("Frame.opCode", frame.getOpCode(), is(OpCode.CONTINUATION));
                actualPayload.put(frame.getPayload());
            }
            actualPayload.flip();
            ByteBufferAssert.assertEquals("Actual Message Payload", actualPayload, expectedMessage);
        }
        catch (Throwable t)
        {
            LOG.info("Actual Message Payload {}", BufferUtil.toDetailString(actualPayload));
            throw t;
        }
    }

    public void assertExpected(BlockingQueue<Frame> framesQueue, List<Frame> expect) throws InterruptedException
    {
        int expectedCount = expect.size();

        String prefix;
        for (int i = 0; i < expectedCount; i++)
        {
            prefix = "Frame[" + i + "]";

            Frame expected = expect.get(i);
            Frame actual = framesQueue.poll(5, TimeUnit.SECONDS);
            assertThat(prefix + ".poll", actual, notNullValue());

            if (LOG.isDebugEnabled())
            {
                if (actual.getOpCode() == OpCode.CLOSE)
                    LOG.debug("{} CloseFrame: {}", prefix, new CloseStatus(actual.getPayload()));
                else
                    LOG.debug("{} {}", prefix, actual);
            }

            assertThat(prefix + ".opcode", OpCode.name(actual.getOpCode()), is(OpCode.name(expected.getOpCode())));
            prefix += "(op=" + actual.getOpCode() + "," + (actual.isFin() ? "" : "!") + "fin)";
            if (expected.getOpCode() == OpCode.CLOSE)
            {
                CloseStatus expectedClose = new CloseStatus(expected.getPayload());
                CloseStatus actualClose = new CloseStatus(actual.getPayload());
                assertThat(prefix + ".code", actualClose.getCode(), is(expectedClose.getCode()));
            }
            else if (expected.hasPayload())
            {
                if (expected.getOpCode() == OpCode.TEXT)
                {
                    String expectedText = expected.getPayloadAsUTF8();
                    String actualText = actual.getPayloadAsUTF8();
                    assertThat(prefix + ".text-payload", actualText, is(expectedText));
                }
                else
                {
                    ByteBufferAssert.assertEquals(prefix + ".payload", expected.getPayload(), actual.getPayload());
                }
            }
            else
            {
                assertThat(prefix + ".payloadLength", actual.getPayloadLength(), is(0));
            }
        }
    }

    public static class RawUpgradeRequest extends CoreClientUpgradeRequest
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
        private final CountDownLatch openLatch = new CountDownLatch(1);
        private EndPoint endPoint;
        private CoreSession coreSession;
        private final AutoLock lock = new AutoLock();

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
            coreSession.demand();
        }

        @Override
        public void onFrame(Frame frame, Callback callback)
        {
            try (AutoLock ignored = lock.lock())
            {
                receivedFrames.add(Frame.copy(frame));
                callback.succeeded();
            }
            coreSession.demand();
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
                assertTrue(openLatch.await(5, TimeUnit.SECONDS));
            }
            catch (InterruptedException e)
            {
                throw new IOException(e);
            }

            try (AutoLock ignored = lock.lock())
            {
                FutureCallback callback = new FutureCallback();
                endPoint.write(callback, buffer);
                callback.block();
            }
        }
    }
}
