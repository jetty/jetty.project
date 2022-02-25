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
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.core.internal.Generator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test behavior of Parser with payload length parsing (per RFC6455)
 */
public class ParsePayloadLengthTest
{
    public static Stream<Arguments> data()
    {
        return Stream.of(
            // -- small 7-bit payload length format (RFC6455)
            Arguments.of(0, "Autobahn Server Testcase 1.1.1"),
            Arguments.of(1, "Jetty Testcase - 1B"),
            Arguments.of(125, "Autobahn Server Testcase 1.1.2"),
            // -- medium 2 byte payload length format
            Arguments.of(126, "Autobahn Server Testcase 1.1.3"),
            Arguments.of(127, "Autobahn Server Testcase 1.1.4"),
            Arguments.of(128, "Autobahn Server Testcase 1.1.5"),
            Arguments.of(65535, "Autobahn Server Testcase 1.1.6"),
            // -- large 8 byte payload length
            Arguments.of(65536, "Autobahn Server Testcase 1.1.7"),
            Arguments.of(500 * 1024, "Jetty Testcase - 500KB"),
            Arguments.of(10 * 1024 * 1024, "Jetty Testcase - 10MB")
        );
    }

    @ParameterizedTest(name = "size={0} {1}")
    @MethodSource("data")
    public void testPayloadLength(int size, String description) throws InterruptedException
    {
        ParserCapture capture = new ParserCapture();
        capture.getCoreSession().setMaxFrameSize(0);

        ByteBuffer raw = BufferUtil.allocate(size + Generator.MAX_HEADER_LENGTH);
        BufferUtil.clearToFill(raw);

        // Create text frame
        RawFrameBuilder.putOpFin(raw, OpCode.TEXT, true);
        RawFrameBuilder.putLength(raw, size, false); // len of closeCode
        byte[] payload = new byte[size];
        Arrays.fill(payload, (byte)'x');
        raw.put(payload);

        // parse buffer
        BufferUtil.flipToFlush(raw, 0);
        capture.parse(raw);

        // validate frame
        Frame frame = capture.framesQueue.poll(1, TimeUnit.SECONDS);
        assertThat("Frame opcode", frame.getOpCode(), is(OpCode.TEXT));
        assertThat("Frame payloadLength", frame.getPayloadLength(), is(size));
        if (size > 0)
            assertThat("Frame payload.remaining", frame.getPayload().remaining(), is(size));
        else
            assertTrue(BufferUtil.isEmpty(frame.getPayload()), "Frame payload");
    }
}
