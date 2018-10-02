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

package org.eclipse.jetty.websocket.tests.server;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.eclipse.jetty.toolchain.test.Hex;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.tests.DataUtils;
import org.eclipse.jetty.websocket.tests.TestWebSocket;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests of Known Good UTF8 sequences.
 * <p>
 * Should be preserved / echoed back, with normal close code.
 */
public class Text_GoodUtf8Test extends AbstractLocalServerCase
{
    public static Stream<Arguments> data()
    {
        // The various Good UTF8 sequences as a String (hex form)
        return Stream.of(
            // @formatter:off
            // - combination of simple 1 byte characters and unicode code points
            Arguments.of("6.2.1", "48656C6C6F2DC2B540C39FC3B6C3A4C3BCC3A0C3A12D5554462D382121"),
            // - simple valid UTF8 sequence
            Arguments.of("6.5.1", "CEBAE1BDB9CF83CEBCCEB5"),
            // - multi-byte code points
            Arguments.of("6.6.11", "CEBAE1BDB9CF83CEBCCEB5"),
            Arguments.of("6.6.2", "CEBA"),
            Arguments.of("6.6.5", "CEBAE1BDB9"),
            Arguments.of("6.6.7", "CEBAE1BDB9CF83"),
            Arguments.of("6.6.9", "CEBAE1BDB9CF83CEBC"),
            // - first possible sequence of a certain length (1 code point)
            Arguments.of("6.7.1", "00"),
            Arguments.of("6.7.2", "C280"),
            Arguments.of("6.7.3", "E0A080"),
            Arguments.of("6.7.4", "F0908080"),
            // - last possible sequence of a certain length (1 code point)
            Arguments.of("6.9.1", "7F"),
            Arguments.of("6.9.2", "DFBF"),
            Arguments.of("6.9.3", "EFBFBF"),
            Arguments.of("6.9.4", "F48FBFBF"),
            // - other boundary conditions
            Arguments.of("6.11.1", "ED9FBF"),
            Arguments.of("6.11.2", "EE8080"),
            Arguments.of("6.11.3", "EFBFBD"),
            Arguments.of("6.11.4", "F48FBFBF"),
            // - non character code points
            Arguments.of("6.22.1", "EFBFBE"),
            Arguments.of("6.22.2", "EFBFBF"),
            Arguments.of("6.22.3", "F09FBFBE"),
            Arguments.of("6.22.4", "F09FBFBF"),
            Arguments.of("6.22.5", "F0AFBFBE"),
            Arguments.of("6.22.6", "F0AFBFBF"),
            Arguments.of("6.22.7", "F0BFBFBE"),
            Arguments.of("6.22.8", "F0BFBFBF"),
            Arguments.of("6.22.9", "F18FBFBE"),
            Arguments.of("6.22.10", "F18FBFBF"),
            Arguments.of("6.22.11", "F19FBFBE"),
            Arguments.of("6.22.12", "F19FBFBF"),
            Arguments.of("6.22.13", "F1AFBFBE"),
            Arguments.of("6.22.14", "F1AFBFBF"),
            Arguments.of("6.22.15", "F1BFBFBE"),
            Arguments.of("6.22.16", "F1BFBFBF"),
            Arguments.of("6.22.17", "F28FBFBE"),
            Arguments.of("6.22.18", "F28FBFBF"),
            Arguments.of("6.22.19", "F29FBFBE"),
            Arguments.of("6.22.20", "F29FBFBF"),
            Arguments.of("6.22.21", "F2AFBFBE"),
            Arguments.of("6.22.22", "F2AFBFBF"),
            Arguments.of("6.22.23", "F2BFBFBE"),
            Arguments.of("6.22.24", "F2BFBFBF"),
            Arguments.of("6.22.25", "F38FBFBE"),
            Arguments.of("6.22.26", "F38FBFBF"),
            Arguments.of("6.22.27", "F39FBFBE"),
            Arguments.of("6.22.28", "F39FBFBF"),
            Arguments.of("6.22.29", "F3AFBFBE"),
            Arguments.of("6.22.30", "F3AFBFBF"),
            Arguments.of("6.22.31", "F3BFBFBE"),
            Arguments.of("6.22.32", "F3BFBFBF"),
            Arguments.of("6.22.33", "F48FBFBE"),
            Arguments.of("6.22.34", "F48FBFBF"),
            // - unicode replacement character
            Arguments.of("6.23.1", "EFBFBD")
            // @formatter:on
        );
    }
    
    @ParameterizedTest(name = "{0} - {1}")
    @MethodSource("data")
    public void assertEchoTextMessage(String testId, String msg) throws Exception
    {
        LOG.debug("Test ID: {}", testId);
        ByteBuffer msgBuffer = Hex.asByteBuffer(msg);

        List<Frame> send = new ArrayList<>();
        send.add(new Frame(OpCode.TEXT).setPayload(msgBuffer));
        send.add(CloseStatus.toFrame(StatusCode.NORMAL));
        
        List<Frame> expect = new ArrayList<>();
        expect.add(new Frame(OpCode.TEXT).setPayload(DataUtils.copyOf(msgBuffer)));
        expect.add(CloseStatus.toFrame(StatusCode.NORMAL));
    
        try (TestWebSocket session = server.newClient())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
}
