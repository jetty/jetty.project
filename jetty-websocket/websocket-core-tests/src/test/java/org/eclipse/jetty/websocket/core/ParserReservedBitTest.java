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

package org.eclipse.jetty.websocket.core;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.core.exception.ProtocolException;
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
    private void expectProtocolException(List<Frame> frames)
    {
        ParserCapture parserCapture = new ParserCapture();

        // generate raw bytebuffer of provided frames
        int size = frames.stream().mapToInt(frame -> frame.getPayloadLength() + Generator.MAX_HEADER_LENGTH).sum();
        Generator generator = new Generator();
        ByteBuffer raw = BufferUtil.allocate(size);
        frames.forEach(frame -> generator.generateWholeFrame(frame, raw));

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
    public void testCase31()
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
    public void testCase32()
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
    public void testCase33()
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
    public void testCase34()
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
    public void testCase35()
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
    public void testCase36()
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
    public void testCase37()
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
