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

import junit.framework.AssertionFailedError;
import org.eclipse.jetty.io.ByteArrayEndPoint;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.toolchain.test.ByteBufferAssert;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.core.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.core.extensions.ExtensionStack;
import org.eclipse.jetty.websocket.core.extensions.WebSocketExtensionRegistry;
import org.eclipse.jetty.websocket.core.frames.Frame;
import org.eclipse.jetty.websocket.core.frames.OpCode;
import org.eclipse.jetty.websocket.core.io.BatchMode;
import org.eclipse.jetty.websocket.core.io.WebSocketConnection;
import org.hamcrest.CoreMatchers;
import org.hamcrest.Matchers;

import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

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
    public interface Exec
    {
        void exec() throws IOException;
    }

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
        this.clientParser = new Parser(clientPolicy, bufferPool);
        this.frameCapture = new FrameCapture(this.clientParser);
    }

    public void assertExpected(BlockingQueue<Frame> framesQueue, List<Frame> expect) throws InterruptedException
    {
        int expectedCount = expect.size();

        String prefix;
        for (int i = 0; i < expectedCount; i++)
        {
            prefix = "Frame[" + i + "]";

            Frame expected = expect.get(i);
            if (LOG.isDebugEnabled())
            {
                LOG.debug("assertExpected() - {} poll", prefix);
            }

            Frame actual = framesQueue.poll(3, TimeUnit.SECONDS);
            assertThat(prefix + ".poll", actual, notNullValue());

            if (LOG.isDebugEnabled())
            {
                if (actual.getOpCode() == OpCode.CLOSE)
                    LOG.debug("{} CloseFrame: {}", prefix, new CloseStatus(actual.getPayload()));
                else
                    LOG.debug("{} {}", prefix, actual);
            }

            assertThat(prefix + ".opcode "+actual, OpCode.name(actual.getOpCode()), Matchers.is(OpCode.name(expected.getOpCode())));
            prefix += "(op=" + actual.getOpCode() + "," + (actual.isFin() ? "" : "!") + "fin)";
            if (expected.getOpCode() == OpCode.CLOSE)
            {
                CloseStatus expectedClose = new CloseStatus(expected.getPayload());
                CloseStatus actualClose = new CloseStatus(actual.getPayload());
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
    public void expect(List<Frame> expected) throws InterruptedException
    {
        // TODO: this needed? frameCapture.waitUntilClosed();
        ByteBuffer output = this.remoteEndPoint.takeOutput();
        if (LOG.isDebugEnabled())
        {
            LOG.debug("raw output from remote = {}", BufferUtil.toDetailString(output));
        }
        frameCapture.parse(output);

        assertExpected(frameCapture.receivedFrames, expected);
    }

    // TODO: replace with junit5 version when junit5 is available
    public <T extends Throwable> T assertThrows(Class<T> expected, Exec exec) throws IOException
    {
        try {
            exec.exec();
        }
        catch (IOException e)
        {
            throw e;
        }
        catch (Throwable actual) {
            return expected.cast(actual);
        }

        throw new AssertionFailedError("Expected exception of type <" + expected.getName() + ">");
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
        sendBuffer(fuzzGenerator.asBuffer(frames));
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
    public void sendSegmented(List<Frame> frames, int segmentSize) throws IOException
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
    public static class FuzzGenerator extends Generator
    {
        // Client side framing mask
        private static final byte[] MASK = {0x11, 0x22, 0x33, 0x44};
        private final boolean applyMask;

        public FuzzGenerator(WebSocketPolicy policy, ByteBufferPool bufferPool)
        {
            super(policy, bufferPool);
            applyMask = (getBehavior() == WebSocketBehavior.CLIENT);
        }

        public ByteBuffer asBuffer(List<Frame> frames)
        {
            int bufferLength = frames.stream().mapToInt((f) -> f.getPayloadLength() + Generator.MAX_HEADER_LENGTH).sum();
            ByteBuffer buffer = ByteBuffer.allocate(bufferLength);
            generate(buffer, frames);
            BufferUtil.flipToFlush(buffer, 0);
            return buffer;
        }

        public ByteBuffer asBuffer(Frame... frames)
        {
            int bufferLength = Stream.of(frames).mapToInt((f) -> f.getPayloadLength() + Generator.MAX_HEADER_LENGTH).sum();
            ByteBuffer buffer = ByteBuffer.allocate(bufferLength);

            generate(buffer, frames);
            BufferUtil.flipToFlush(buffer, 0);
            return buffer;
        }

        public void generate(ByteBuffer buffer, List<Frame> frames)
        {
            // Generate frames
            for (Frame f : frames)
            {
                if (applyMask)
                    f.setMask(MASK);

                generateWholeFrame(f, buffer);
            }
        }

        public void generate(ByteBuffer buffer, Frame... frames)
        {
            // Generate frames
            for (Frame f : frames)
            {
                if (applyMask)
                    f.setMask(MASK);

                generateWholeFrame(f, buffer);
            }
        }

        public ByteBuffer generate(Frame frame)
        {
            int bufferLength = frame.getPayloadLength() + Generator.MAX_HEADER_LENGTH;
            ByteBuffer buffer = ByteBuffer.allocate(bufferLength);
            if (applyMask)
                frame.setMask(MASK);
            generateWholeFrame(frame, buffer);
            BufferUtil.flipToFlush(buffer, 0);
            return buffer;
        }

        public void generate(ByteBuffer buffer, Frame frame)
        {
            if (applyMask)
                frame.setMask(MASK);
            generateWholeFrame(frame, buffer);
        }
    }
    

    public static class RemoteWholeEchoHandler extends AbstractTestFrameHandler
    {

        @Override
        protected void onText(Utf8StringBuilder utf8, Callback callback, boolean fin)
        {
            if (fin)
                getCoreSession().sendFrame(new Frame(OpCode.TEXT).setPayload(utf8.toString()),callback,BatchMode.OFF);
            else
                callback.succeeded();
        }

        @Override
        protected void onBinary(ByteBuffer payload, Callback callback, boolean fin)
        {
            if (fin)
                getCoreSession().sendFrame(new Frame(OpCode.BINARY).setPayload(BufferUtil.toArray(payload)),callback,BatchMode.OFF);
            else
                callback.succeeded();
        }
    }

    public static class FrameCapture
    {
        private final static Logger LOG = Log.getLogger(FrameCapture.class);
        private final Parser parser;
        private final BlockingQueue<Frame> receivedFrames = new LinkedBlockingQueue<>();
        private final CountDownLatch closedLatch = new CountDownLatch(1);

        FrameCapture(Parser parser)
        {
            this.parser = parser;
        }
        
        public void parse(ByteBuffer buffer)
        {
            while (BufferUtil.hasContent(buffer))
            {
                Frame frame = parser.parse(buffer);
                if (frame==null)
                    break;
                if (!onFrame(frame))
                    break;
            }
        }
        
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

            receivedFrames.offer(Frame.copy(frame));

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
