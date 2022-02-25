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
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.eclipse.jetty.util.BufferUtil;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

/**
 * Test behavior of Parser when encountering good / valid close status codes (per RFC6455)
 */
public class ParserGoodCloseStatusCodesTest
{
    public static Stream<Arguments> data()
    {
        return Stream.of(
            Arguments.of(1000, "Autobahn Server Testcase 7.7.1"),
            Arguments.of(1001, "Autobahn Server Testcase 7.7.2"),
            Arguments.of(1002, "Autobahn Server Testcase 7.7.3"),
            Arguments.of(1003, "Autobahn Server Testcase 7.7.4"),
            Arguments.of(1007, "Autobahn Server Testcase 7.7.5"),
            Arguments.of(1008, "Autobahn Server Testcase 7.7.6"),
            Arguments.of(1009, "Autobahn Server Testcase 7.7.7"),
            Arguments.of(1010, "Autobahn Server Testcase 7.7.8"),
            Arguments.of(1011, "Autobahn Server Testcase 7.7.9"),
            // These must be allowed, and cannot result in a ProtocolException
            Arguments.of(1012, "IANA Assigned"), // Now IANA Assigned
            Arguments.of(1013, "IANA Assigned"), // Now IANA Assigned
            Arguments.of(1014, "IANA Assigned"), // Now IANA Assigned
            Arguments.of(3000, "Autobahn Server Testcase 7.7.10"),
            Arguments.of(3099, "Autobahn Server Testcase 7.7.11"),
            Arguments.of(4000, "Autobahn Server Testcase 7.7.12"),
            Arguments.of(4099, "Autobahn Server Testcase 7.7.13")
        );
    }

    @ParameterizedTest(name = "closeCode={0} {1}")
    @MethodSource("data")
    public void testGoodCloseCode(int closeCode, String description) throws InterruptedException
    {
        ParserCapture capture = new ParserCapture();

        ByteBuffer raw = BufferUtil.allocate(256);
        BufferUtil.clearToFill(raw);

        // add close frame
        RawFrameBuilder.putOpFin(raw, OpCode.CLOSE, true);
        RawFrameBuilder.putLength(raw, 2, false); // len of closeCode
        raw.putChar((char)closeCode); // 2 bytes for closeCode

        // parse buffer
        BufferUtil.flipToFlush(raw, 0);
        capture.parse(raw);
        Frame frame = capture.framesQueue.poll(1, TimeUnit.SECONDS);
        assertThat("Frame opcode", frame.getOpCode(), is(OpCode.CLOSE));
        CloseStatus closeStatus = new CloseStatus(frame.getPayload());
        assertThat("CloseStatus.code", closeStatus.getCode(), is(closeCode));
        assertThat("CloseStatus.reason", closeStatus.getReason(), nullValue());
    }

    @ParameterizedTest(name = "closeCode={0} {1}")
    @MethodSource("data")
    public void testGoodCloseCodeWithReasonPhrase(int closeCode, String description) throws InterruptedException
    {
        ParserCapture capture = new ParserCapture();

        ByteBuffer raw = BufferUtil.allocate(256);
        BufferUtil.clearToFill(raw);

        // add close frame
        RawFrameBuilder.putOpFin(raw, OpCode.CLOSE, true);
        RawFrameBuilder.putLength(raw, 2 + 5, false); // len of closeCode + reason phrase
        raw.putChar((char)closeCode); // 2 bytes for closeCode
        raw.put("hello".getBytes(UTF_8));

        // parse buffer
        BufferUtil.flipToFlush(raw, 0);
        capture.parse(raw);
        Frame frame = capture.framesQueue.poll(1, TimeUnit.SECONDS);
        assertThat("Frame opcode", frame.getOpCode(), is(OpCode.CLOSE));
        CloseStatus closeStatus = new CloseStatus(frame.getPayload());
        assertThat("CloseStatus.code", closeStatus.getCode(), is(closeCode));
        assertThat("CloseStatus.reason", closeStatus.getReason(), is("hello"));
    }
}
