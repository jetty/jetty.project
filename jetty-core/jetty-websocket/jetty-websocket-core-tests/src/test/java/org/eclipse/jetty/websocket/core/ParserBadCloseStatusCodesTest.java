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
 * Test behavior of Parser when encountering bad / forbidden close status codes (per RFC6455)
 */
public class ParserBadCloseStatusCodesTest
{
    public static Stream<Arguments> data()
    {
        return Stream.of(
            Arguments.of(0, "Autobahn Server Testcase 7.9.1"),
            Arguments.of(999, "Autobahn Server Testcase 7.9.2"),
            Arguments.of(1004, "Autobahn Server Testcase 7.9.3"), // RFC6455/UNDEFINED
            Arguments.of(1005, "Autobahn Server Testcase 7.9.4"), // RFC6455/Cannot Be Transmitted
            Arguments.of(1006, "Autobahn Server Testcase 7.9.5"), // RFC6455/Cannot Be Transmitted
            // Leaving these 3 here and commented out so they don't get re-added (because they are missing)
            // See ParserGoodCloseStatusCodesTest for new test of these
            // Arguments.of( 1012, "Autobahn Server Testcase 7.9.6"), // Now IANA Defined
            // Arguments.of( 1013, "Autobahn Server Testcase 7.9.7"), // Now IANA Defined
            // Arguments.of( 1014, "Autobahn Server Testcase 7.9.8"), // Now IANA Defined
            Arguments.of(1015, "Autobahn Server Testcase 7.9.9"), // RFC6455/Cannot Be Transmitted
            Arguments.of(1016, "Autobahn Server Testcase 7.9.10"), // RFC6455
            Arguments.of(1100, "Autobahn Server Testcase 7.9.11"), // RFC6455
            Arguments.of(2000, "Autobahn Server Testcase 7.9.12"), // RFC6455
            Arguments.of(2999, "Autobahn Server Testcase 7.9.13"), // RFC6455
            // -- close status codes, with undefined events in spec
            Arguments.of(5000, "Autobahn Server Testcase 7.13.1"), // RFC6455/Undefined
            Arguments.of(65535, "Autobahn Server Testcase 7.13.2") // RFC6455/Undefined
        );
    }

    private final ByteBufferPool bufferPool = new MappedByteBufferPool();

    @ParameterizedTest(name = "closeCode={0} {1}")
    @MethodSource("data")
    public void testBadStatusCode(int closeCode, String description)
    {
        ParserCapture capture = new ParserCapture();

        ByteBuffer raw = BufferUtil.allocate(256);
        BufferUtil.clearToFill(raw);

        // add close frame
        RawFrameBuilder.putOpFin(raw, OpCode.CLOSE, true);
        RawFrameBuilder.putLength(raw, 2, false); // len of closeCode
        raw.putShort((short)closeCode); // 2 bytes for closeCode

        // parse buffer
        BufferUtil.flipToFlush(raw, 0);
        try (StacklessLogging ignore = new StacklessLogging(Parser.class))
        {
            Exception e = assertThrows(ProtocolException.class, () -> capture.parse(raw));
            assertThat(e.getMessage(), containsString("Invalid CLOSE Code: " + closeCode));
        }
    }

    @ParameterizedTest(name = "closeCode={0} {1}")
    @MethodSource("data")
    public void testBadStatusCodeWithReasonPhrase(int closeCode, String description)
    {
        ParserCapture capture = new ParserCapture();

        ByteBuffer raw = BufferUtil.allocate(256);
        BufferUtil.clearToFill(raw);

        // add close frame
        RawFrameBuilder.putOpFin(raw, OpCode.CLOSE, true);
        RawFrameBuilder.putLength(raw, 2 + 5, false); // len of closeCode + reason phrase
        raw.putShort((short)closeCode); // 2 bytes for closeCode
        raw.put("hello".getBytes(UTF_8));

        // parse buffer
        BufferUtil.flipToFlush(raw, 0);
        try (StacklessLogging ignore = new StacklessLogging(Parser.class))
        {
            Exception e = assertThrows(ProtocolException.class, () -> capture.parse(raw));
            assertThat(e.getMessage(), containsString("Invalid CLOSE Code: " + closeCode));
        }
    }
}
