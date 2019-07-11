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

package org.eclipse.jetty.websocket.core;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.websocket.core.internal.Generator;
import org.eclipse.jetty.websocket.core.internal.Parser;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Test various RSV violations
 */
public class ParserReservedBitTest
{
    private ByteBufferPool bufferPool = new MappedByteBufferPool();

    private void expectProtocolException(List<Frame> frames)
    {
        ParserCapture parserCapture = new ParserCapture();

        // generate raw bytebuffer of provided frames
        int size = frames.stream().mapToInt(frame -> frame.getPayloadLength() + Generator.MAX_HEADER_LENGTH).sum();
        ByteBuffer raw = BufferUtil.allocate(size);
        BufferUtil.clearToFill(raw);
        Generator generator = new Generator(bufferPool);
        frames.forEach(frame -> generator.generateWholeFrame(frame, raw));
        BufferUtil.flipToFlush(raw, 0);

        // parse buffer
        try (StacklessLogging ignore = new StacklessLogging(Parser.class))
        {
            Exception e = assertThrows(ProtocolException.class, () -> parserCapture.parse(raw));
            assertThat(e.getMessage(), containsString("RSV"));
        }
    }

    /**
     * Send small text frame, with RSV1 == true, with no extensions defined.
     * <p>
     * From Autobahn WebSocket Server Testcase 3.1
     * </p>
     *
     * @throws Exception on test failure
     */
    @Test
    public void testCase3_1()
    {
        List<Frame> send = new ArrayList<>();
        send.add(new Frame(OpCode.TEXT).setPayload("small").setRsv1(true)); // intentionally bad

        expectProtocolException(send);
    }

    /**
     * Send small text frame, send again with RSV2 == true, then ping, with no extensions defined.
     * <p>
     * From Autobahn WebSocket Server Testcase 3.2
     * </p>
     *
     * @throws Exception on test failure
     */
    @Test
    public void testCase3_2()
    {
        List<Frame> send = new ArrayList<>();
        send.add(new Frame(OpCode.TEXT).setPayload("small"));
        send.add(new Frame(OpCode.TEXT).setPayload("small").setRsv2(true)); // intentionally bad
        send.add(new Frame(OpCode.PING).setPayload("ping"));

        expectProtocolException(send);
    }

    /**
     * Send small text frame, send again with (RSV1 & RSV2), then ping, with no extensions defined.
     * <p>
     * From Autobahn WebSocket Server Testcase 3.3
     * </p>
     *
     * @throws Exception on test failure
     */
    @Test
    public void testCase3_3()
    {
        List<Frame> send = new ArrayList<>();
        send.add(new Frame(OpCode.TEXT).setPayload("small"));
        send.add(new Frame(OpCode.TEXT).setPayload("small").setRsv1(true).setRsv2(true)); // intentionally bad
        send.add(new Frame(OpCode.PING).setPayload("ping"));

        expectProtocolException(send);
    }

    /**
     * Send small text frame, send again with (RSV3), then ping, with no extensions defined.
     * <p>
     * From Autobahn WebSocket Server Testcase 3.4
     * </p>
     *
     * @throws Exception on test failure
     */
    @Test
    public void testCase3_4()
    {
        List<Frame> send = new ArrayList<>();
        send.add(new Frame(OpCode.TEXT).setPayload("small"));
        send.add(new Frame(OpCode.TEXT).setPayload("small").setRsv3(true)); // intentionally bad
        send.add(new Frame(OpCode.PING).setPayload("ping"));

        expectProtocolException(send);
    }

    /**
     * Send binary frame with (RSV3 & RSV1), with no extensions defined.
     * <p>
     * From Autobahn WebSocket Server Testcase 3.5
     * </p>
     *
     * @throws Exception on test failure
     */
    @Test
    public void testCase3_5()
    {
        byte[] payload = new byte[8];
        Arrays.fill(payload, (byte)0xFF);

        List<Frame> send = new ArrayList<>();
        send.add(new Frame(OpCode.BINARY).setPayload(payload).setRsv3(true).setRsv1(true)); // intentionally bad

        expectProtocolException(send);
    }

    /**
     * Send ping frame with (RSV3 & RSV2), with no extensions defined.
     * <p>
     * From Autobahn WebSocket Server Testcase 3.6
     * </p>
     *
     * @throws Exception on test failure
     */
    @Test
    public void testCase3_6()
    {
        byte[] payload = new byte[8];
        Arrays.fill(payload, (byte)0xFF);

        List<Frame> send = new ArrayList<>();
        send.add(new Frame(OpCode.PING).setPayload(payload).setRsv3(true).setRsv2(true)); // intentionally bad

        expectProtocolException(send);
    }

    /**
     * Send close frame with (RSV3 & RSV2 & RSV1), with no extensions defined.
     * <p>
     * From Autobahn WebSocket Server Testcase 3.7
     * </p>
     *
     * @throws Exception on test failure
     */
    @Test
    public void testCase3_7()
    {
        List<Frame> send = new ArrayList<>();
        Frame frame = CloseStatus.toFrame(1000);
        frame.setRsv1(true);
        frame.setRsv2(true);
        frame.setRsv3(true);
        send.add(frame); // intentionally bad

        expectProtocolException(send);
    }
}
