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
import java.util.stream.Stream;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.core.exception.ProtocolException;
import org.eclipse.jetty.websocket.core.internal.Parser;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Test behavior of Parser when encountering bad / forbidden opcodes (per RFC6455)
 */
public class ParserBadOpCodesTest
{
    public static Stream<Arguments> data()
    {
        return Stream.of(
            Arguments.of((byte)3, "Autobahn Server Testcase 4.1.1"),
            Arguments.of((byte)4, "Autobahn Server Testcase 4.1.2"),
            Arguments.of((byte)5, "Autobahn Server Testcase 4.1.3"),
            Arguments.of((byte)6, "Autobahn Server Testcase 4.1.4"),
            Arguments.of((byte)7, "Autobahn Server Testcase 4.1.5"),
            Arguments.of((byte)11, "Autobahn Server Testcase 4.2.1"),
            Arguments.of((byte)12, "Autobahn Server Testcase 4.2.2"),
            Arguments.of((byte)13, "Autobahn Server Testcase 4.2.3"),
            Arguments.of((byte)14, "Autobahn Server Testcase 4.2.4"),
            Arguments.of((byte)15, "Autobahn Server Testcase 4.2.5")
        );
    }

    private final ByteBufferPool bufferPool = new MappedByteBufferPool();

    @ParameterizedTest(name = "opcode={0} {1}")
    @MethodSource("data")
    public void testBadOpCode(byte opcode, String description)
    {
        ParserCapture capture = new ParserCapture();

        ByteBuffer raw = BufferUtil.allocate(256);
        BufferUtil.flipToFill(raw);

        // add bad opcode frame
        RawFrameBuilder.putOpFin(raw, opcode, true);
        RawFrameBuilder.putLength(raw, 0, false);

        // parse buffer
        BufferUtil.flipToFlush(raw, 0);
        try (StacklessLogging ignore = new StacklessLogging(Parser.class))
        {
            Exception e = assertThrows(ProtocolException.class, () -> capture.parse(raw));
            assertThat(e.getMessage(), containsString("Unknown opcode: " + opcode));
        }
    }

    @ParameterizedTest(name = "opcode={0} {1}")
    @MethodSource("data")
    public void testTextBadOpCodePing(byte opcode, String description)
    {
        ParserCapture capture = new ParserCapture();

        ByteBuffer raw = BufferUtil.allocate(256);
        BufferUtil.flipToFill(raw);

        // adding text frame
        ByteBuffer msg = BufferUtil.toBuffer("hello", UTF_8);
        RawFrameBuilder.putOpFin(raw, OpCode.TEXT, true);
        RawFrameBuilder.putLength(raw, msg.remaining(), false);
        BufferUtil.put(msg, raw);

        // adding bad opcode frame
        RawFrameBuilder.putOpFin(raw, opcode, true);
        RawFrameBuilder.putLength(raw, 0, false);

        // adding ping frame
        RawFrameBuilder.putOpFin(raw, OpCode.PING, true);
        RawFrameBuilder.putLength(raw, 0, false);

        // parse provided buffer
        BufferUtil.flipToFlush(raw, 0);
        try (StacklessLogging ignore = new StacklessLogging(Parser.class))
        {
            Exception e = assertThrows(ProtocolException.class, () -> capture.parse(raw));
            assertThat(e.getMessage(), containsString("Unknown opcode: " + opcode));
        }
    }
}
