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

package org.eclipse.jetty.ee9.websocket.jakarta.tests;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.toolchain.test.ByteBufferAssert;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

public interface Fuzzer extends AutoCloseable
{
    ByteBuffer asNetworkBuffer(List<Frame> frames);

    /**
     * For some Fuzzers implementations, this triggers a send EOF.
     * TODO: should probably be called shutdownOutput()
     */
    void eof();

    /**
     * Assert that the provided expected WebSocketFrames are what was received
     * from the remote.
     *
     * @param frames the expected frames
     */
    void expect(List<Frame> frames) throws InterruptedException;

    /**
     * Assert that the following frames contains the expected whole message.
     *
     * @param framesQueue the captured frames
     * @param expectedDataOp the expected message data type ({@link OpCode#BINARY} or {@link OpCode#TEXT})
     * @param expectedMessage the expected message
     */
    void expectMessage(BlockingQueue<Frame> framesQueue, byte expectedDataOp, ByteBuffer expectedMessage) throws InterruptedException;

    BlockingQueue<Frame> getOutputFrames();

    /**
     * Send raw bytes
     *
     * @param buffer the buffer
     */
    void send(ByteBuffer buffer) throws IOException;

    /**
     * Send some of the raw bytes
     *
     * @param buffer the buffer
     * @param length the number of bytes to send from buffer
     */
    void send(ByteBuffer buffer, int length) throws IOException;

    /**
     * Generate a single ByteBuffer representing the entire
     * list of generated frames, and send it as a single
     * buffer
     *
     * @param frames the list of frames to send
     */
    void sendBulk(List<Frame> frames) throws IOException;

    /**
     * Generate a ByteBuffer for each frame, and send each as
     * unique buffer containing each frame.
     *
     * @param frames the list of frames to send
     */
    void sendFrames(List<Frame> frames) throws IOException;

    /**
     * Generate a ByteBuffer for each frame, and send each as
     * unique buffer containing each frame.
     *
     * @param frames the list of frames to send
     */
    void sendFrames(Frame... frames) throws IOException;

    /**
     * Generate a single ByteBuffer representing the entire list
     * of generated frames, and send segments of {@code segmentSize}
     * to remote as individual buffers.
     *
     * @param frames the list of frames to send
     * @param segmentSize the size of each segment to send
     */
    void sendSegmented(List<Frame> frames, int segmentSize) throws IOException;

    abstract class Adapter
    {
        protected final Logger logger;

        public Adapter()
        {
            logger = LoggerFactory.getLogger(this.getClass());
        }

        public void expectMessage(BlockingQueue<Frame> framesQueue, byte expectedDataOp, ByteBuffer expectedMessage) throws InterruptedException
        {
            ByteBuffer actualPayload = ByteBuffer.allocate(expectedMessage.remaining());

            Frame frame = framesQueue.poll(1, TimeUnit.SECONDS);
            assertThat("Initial Frame.opCode", frame.getOpCode(), is(expectedDataOp));

            actualPayload.put(frame.getPayload());
            while (!frame.isFin())
            {
                frame = framesQueue.poll(1, TimeUnit.SECONDS);
                assertThat("Frame.opCode", frame.getOpCode(), is(OpCode.CONTINUATION));
                actualPayload.put(frame.getPayload());
            }
            actualPayload.flip();
            ByteBufferAssert.assertEquals("Actual Message Payload", actualPayload, expectedMessage);
        }

        @SuppressWarnings("Duplicates")
        public void assertExpected(BlockingQueue<Frame> framesQueue, List<Frame> expect) throws InterruptedException
        {
            int expectedCount = expect.size();

            String prefix;
            for (int i = 0; i < expectedCount; i++)
            {
                prefix = "Frame[" + i + "]";

                Frame expected = expect.get(i);
                Frame actual = framesQueue.poll(3, TimeUnit.SECONDS);
                assertThat(prefix + ".poll", actual, notNullValue());

                if (logger.isDebugEnabled())
                {
                    if (actual.getOpCode() == OpCode.CLOSE)
                        logger.debug("{} CloseFrame: {}", prefix, new CloseStatus(actual.getPayload()));
                    else
                        logger.debug("{} {}", prefix, actual);
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
    }
}
