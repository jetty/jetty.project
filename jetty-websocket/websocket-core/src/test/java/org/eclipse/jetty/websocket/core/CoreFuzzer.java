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

package org.eclipse.jetty.websocket.core;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.eclipse.jetty.io.ByteArrayEndPoint;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.toolchain.test.ByteBufferAssert;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.SharedBlockingCallback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.core.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.core.extensions.ExtensionStack;
import org.eclipse.jetty.websocket.core.extensions.WebSocketExtensionRegistry;
import org.eclipse.jetty.websocket.core.frames.BinaryFrame;
import org.eclipse.jetty.websocket.core.frames.CloseFrame;
import org.eclipse.jetty.websocket.core.frames.OpCode;
import org.eclipse.jetty.websocket.core.frames.PongFrame;
import org.eclipse.jetty.websocket.core.frames.TextFrame;
import org.eclipse.jetty.websocket.core.frames.WebSocketFrame;
import org.eclipse.jetty.websocket.core.io.BatchMode;
import org.eclipse.jetty.websocket.core.io.WebSocketConnection;
import org.hamcrest.CoreMatchers;
import org.hamcrest.Matchers;

/**
 * Creates a framework of 2 WebSocket Core instances to help test behaviors within core.
 * <p>
 * <p>
 * Local - the fake "client" in this instance.
 * Client can send raw bytes to remote.
 * Client parses received bytes into Frames that are captured for analysis.
 * Remote - the remote "server" in this instance.
 * Remote receives raw bytes.
 * Remote parses raw bytes.
 * Remote aggregates whole messages before echoing them back.
 * </p>
 */
public class CoreFuzzer implements AutoCloseable
{
    private final static Logger LOG = Log.getLogger(CoreFuzzer.class);
    private final FrameCapture frameCapture;
    private final FrameHandler remoteFrameHandler;
    private final ByteArrayEndPoint remoteEndPoint;
    private final WebSocketChannel channel;
    private final WebSocketConnection connection;
    private final FuzzGenerator fuzzGenerator;
    private final Parser clientParser;

    public CoreFuzzer()
    {
        this(new RemoteWholeEchoHandler());
    }

    public CoreFuzzer(FrameHandler remoteFrameHandler)
    {
        this.remoteFrameHandler = remoteFrameHandler;
        this.frameCapture = new FrameCapture();

        DecoratedObjectFactory objectFactory = new DecoratedObjectFactory();
        ByteBufferPool bufferPool = new MappedByteBufferPool();
        WebSocketPolicy serverPolicy = new WebSocketPolicy(WebSocketBehavior.SERVER);
        WebSocketExtensionRegistry webSocketExtensionRegistry = new WebSocketExtensionRegistry();
        ExtensionStack extensionStack = new ExtensionStack(webSocketExtensionRegistry);
        List<ExtensionConfig> configs = new ArrayList<>();
        extensionStack.negotiate(objectFactory, serverPolicy, bufferPool, configs);
        String subProtocol = null;
        this.channel = new WebSocketChannel(this.remoteFrameHandler, serverPolicy, extensionStack, subProtocol);

        this.remoteEndPoint = new ByteArrayEndPoint();
        this.remoteEndPoint.setGrowOutput(true);
        Executor executor = new QueuedThreadPool();
        boolean validating = true;
        this.connection = new WebSocketConnection(remoteEndPoint, executor, bufferPool, channel, validating);
        this.channel.setWebSocketConnection(connection);
        this.connection.onOpen();

        WebSocketPolicy clientPolicy = new WebSocketPolicy(WebSocketBehavior.CLIENT);
        this.fuzzGenerator = new FuzzGenerator(clientPolicy, bufferPool);
        this.clientParser = new Parser(clientPolicy, bufferPool, frameCapture);
    }

    public void assertExpected(BlockingQueue<WebSocketFrame> framesQueue, List<WebSocketFrame> expect) throws InterruptedException
    {
        int expectedCount = expect.size();

        String prefix;
        for (int i = 0; i < expectedCount; i++)
        {
            prefix = "Frame[" + i + "]";

            WebSocketFrame expected = expect.get(i);
            if (LOG.isDebugEnabled())
            {
                LOG.debug("assertExpected() - {} poll", prefix);
            }

            WebSocketFrame actual = framesQueue.poll(3, TimeUnit.SECONDS);
            assertThat(prefix + ".poll", actual, notNullValue());

            if (LOG.isDebugEnabled())
            {
                if (actual.getOpCode() == OpCode.CLOSE)
                    LOG.debug("{} CloseFrame: {}", prefix, CloseFrame.toCloseStatus(actual.getPayload()));
                else
                    LOG.debug("{} {}", prefix, actual);
            }

            assertThat(prefix + ".opcode", OpCode.name(actual.getOpCode()), Matchers.is(OpCode.name(expected.getOpCode())));
            prefix += "(op=" + actual.getOpCode() + "," + (actual.isFin() ? "" : "!") + "fin)";
            if (expected.getOpCode() == OpCode.CLOSE)
            {
                CloseStatus expectedClose = CloseFrame.toCloseStatus(expected.getPayload());
                CloseStatus actualClose = CloseFrame.toCloseStatus(actual.getPayload());
                assertThat(prefix + ".code", actualClose.getCode(), Matchers.is(expectedClose.getCode()));
            }
            else if (expected.hasPayload())
            {
                if (expected.getOpCode() == OpCode.TEXT)
                {
                    String expectedText = expected.getPayloadAsUTF8();
                    String actualText = actual.getPayloadAsUTF8();
                    assertThat(prefix + ".text-payload", actualText, CoreMatchers.is(expectedText));
                }
                else
                {
                    assertThat(prefix + ".payloadLength", actual.getPayloadLength(), Matchers.is(expected.getPayloadLength()));
                    ByteBufferAssert.assertEquals(prefix + ".payload", expected.getPayload(), actual.getPayload());
                }
            }
            else
            {
                assertThat(prefix + ".payloadLength", actual.getPayloadLength(), Matchers.is(0));
            }
        }
    }

    @Override
    public void close() throws Exception
    {
        this.connection.close();
    }

    /**
     * Assert that the provided expected WebSocketFrames are what was received
     * from the remote.
     *
     * @param expected the expected frames
     */
    public void expect(List<WebSocketFrame> expected) throws InterruptedException
    {
        // TODO: this needed? frameCapture.waitUntilClosed();
        ByteBuffer output = this.remoteEndPoint.takeOutput();
        if (LOG.isDebugEnabled())
        {
            LOG.debug("raw output from remote = {}", BufferUtil.toDetailString(output));
        }
        this.clientParser.parse(output);

        assertExpected(frameCapture.receivedFrames, expected);
    }

    /**
     * Generate a single ByteBuffer representing the entire
     * list of generated frames, and send it as a single
     * buffer
     *
     * @param frames the list of frames to send
     */
    public void sendBulk(List<WebSocketFrame> frames) throws IOException
    {
        sendBuffer(fuzzGenerator.asBuffer(frames));
    }

    /**
     * Generate a ByteBuffer for each frame, and send each as
     * unique buffer containing each frame.
     *
     * @param frames the list of frames to send
     */
    public void sendFrames(List<WebSocketFrame> frames) throws IOException
    {
        for (WebSocketFrame f : frames)
        {
            sendBuffer(fuzzGenerator.generate(f));
        }
    }

    /**
     * Generate a single ByteBuffer representing the entire list
     * of generated frames, and send segments of {@code segmentSize}
     * to remote as individual buffers.
     *
     * @param frames the list of frames to send
     * @param segmentSize the size of each segment to send
     */
    public void sendSegmented(List<WebSocketFrame> frames, int segmentSize) throws IOException
    {
        ByteBuffer buffer = fuzzGenerator.asBuffer(frames);

        while (buffer.remaining() > 0)
        {
            sendPartialBuffer(buffer, segmentSize);
        }
    }

    private void sendBuffer(ByteBuffer buffer) throws IOException
    {
        remoteEndPoint.addInput(buffer);
    }

    private void sendPartialBuffer(ByteBuffer buffer, int length) throws IOException
    {
        int limit = Math.min(length, buffer.remaining());
        ByteBuffer sliced = buffer.slice();
        sliced.limit(limit);
        sendBuffer(sliced);
        buffer.position(buffer.position() + limit);
    }

    /**
     * Generator suitable for generating non-valid content.
     */
    @SuppressWarnings("Duplicates")
    public static class FuzzGenerator extends Generator
    {
        // Client side framing mask
        private static final byte[] MASK = {0x11, 0x22, 0x33, 0x44};
        private final boolean applyMask;

        public FuzzGenerator(WebSocketPolicy policy, ByteBufferPool bufferPool)
        {
            super(policy, bufferPool, false);
            applyMask = (getBehavior() == WebSocketBehavior.CLIENT);
        }

        public ByteBuffer asBuffer(List<WebSocketFrame> frames)
        {
            int bufferLength = frames.stream().mapToInt((f) -> f.getPayloadLength() + Generator.MAX_HEADER_LENGTH).sum();
            ByteBuffer buffer = ByteBuffer.allocate(bufferLength);
            generate(buffer, frames);
            BufferUtil.flipToFlush(buffer, 0);
            return buffer;
        }

        public ByteBuffer asBuffer(WebSocketFrame... frames)
        {
            int bufferLength = Stream.of(frames).mapToInt((f) -> f.getPayloadLength() + Generator.MAX_HEADER_LENGTH).sum();
            ByteBuffer buffer = ByteBuffer.allocate(bufferLength);

            generate(buffer, frames);
            BufferUtil.flipToFlush(buffer, 0);
            return buffer;
        }

        public void generate(ByteBuffer buffer, List<WebSocketFrame> frames)
        {
            // Generate frames
            for (WebSocketFrame f : frames)
            {
                if (applyMask)
                    f.setMask(MASK);

                generateWholeFrame(f, buffer);
            }
        }

        public void generate(ByteBuffer buffer, WebSocketFrame... frames)
        {
            // Generate frames
            for (WebSocketFrame f : frames)
            {
                if (applyMask)
                    f.setMask(MASK);

                generateWholeFrame(f, buffer);
            }
        }

        public ByteBuffer generate(WebSocketFrame frame)
        {
            int bufferLength = frame.getPayloadLength() + Generator.MAX_HEADER_LENGTH;
            ByteBuffer buffer = ByteBuffer.allocate(bufferLength);
            if (applyMask)
                frame.setMask(MASK);
            generateWholeFrame(frame, buffer);
            BufferUtil.flipToFlush(buffer, 0);
            return buffer;
        }

        public void generate(ByteBuffer buffer, WebSocketFrame frame)
        {
            if (applyMask)
                frame.setMask(MASK);
            generateWholeFrame(frame, buffer);
        }
    }

    public static class RemoteWholeEchoHandler extends AbstractWholeMessageHandler
    {
        private final static Logger LOG = Log.getLogger(FrameCapture.class);
        private final SharedBlockingCallback sendBlocker = new SharedBlockingCallback();

        @Override
        public void onClosed(CloseStatus closeStatus) throws Exception
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("onClosed(): {}", closeStatus);
            }
        }

        @Override
        public void onError(Throwable cause) throws Exception
        {
            if (LOG.isDebugEnabled())
            {
                LOG.warn("onError()", cause);
            }
        }

        @Override
        public void onPing(Frame frame, Callback callback)
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("onPing(): {}", frame);
            }
            sendBlocking(new PongFrame().setPayload(frame.getPayload()));
            callback.succeeded();
        }

        @Override
        public void onWholeBinary(ByteBuffer wholeMessage, Callback callback)
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("onWholeBinary(): {}", BufferUtil.toDetailString(wholeMessage));
            }
            sendBlocking(new BinaryFrame().setPayload(copyOf(wholeMessage)));
            callback.succeeded();
        }

        @Override
        public void onWholeText(String wholeMessage, Callback callback)
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("onWholeText(): {}", BufferUtil.toDetailString(BufferUtil.toBuffer(wholeMessage, UTF_8)));
            }
            sendBlocking(new TextFrame().setPayload(wholeMessage));
            callback.succeeded();
        }

        private void sendBlocking(WebSocketFrame frame)
        {
            try (SharedBlockingCallback.Blocker blocker = sendBlocker.acquire())
            {
                channel.sendFrame(frame, blocker, BatchMode.OFF);
            }
            catch (IOException e)
            {
                throw new RuntimeIOException("Unable to send frame: " + frame, e);
            }
        }
    }

    public static class FrameCapture implements Parser.Handler
    {
        private final static Logger LOG = Log.getLogger(FrameCapture.class);
        private final BlockingQueue<WebSocketFrame> receivedFrames = new LinkedBlockingQueue<>();
        private final CountDownLatch closedLatch = new CountDownLatch(1);

        @Override
        public boolean onFrame(Frame frame)
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("onFrame(): {} {}", frame, BufferUtil.toDetailString(frame.getPayload()));
            }

            if (frame.getOpCode() == OpCode.CLOSE)
            {
                // Notify awaiting threads of closed state
                closedLatch.countDown();
            }

            receivedFrames.offer(WebSocketFrame.copy(frame));

            return true;
        }

        public void waitUntilClosed()
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("waitUntilClosed()");
            }

            try
            {
                assertThat("Closed within a reasonable time", closedLatch.await(Timeouts.CLOSE_EVENT_MS, TimeUnit.MILLISECONDS), is(true));
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
    }
}
