//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.websocket.server.ab;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.common.test.Fuzzer;
import org.eclipse.jetty.websocket.common.util.Hex;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests of Known Good UTF8 sequences.
 * <p>
 * Should be preserved / echoed back, with normal close code.
 */
public class TestABCase6GoodUTF extends AbstractABCase
{
    private static final Logger LOG = Log.getLogger(TestABCase6GoodUTF.class);

    public static Stream<Arguments> utfSequences()
    {
        // The various Good UTF8 sequences as a String (hex form)
        List<String[]> data = new ArrayList<>();

        // - combination of simple 1 byte characters and unicode code points
        data.add(new String[]{"6.2.1", "48656C6C6F2DC2B540C39FC3B6C3A4C3BCC3A0C3A12D5554462D382121"});
        // - simple valid UTF8 sequence
        data.add(new String[]{"6.5.1", "CEBAE1BDB9CF83CEBCCEB5"});
        // - multi-byte code points
        data.add(new String[]{"6.6.11", "CEBAE1BDB9CF83CEBCCEB5"});
        data.add(new String[]{"6.6.2", "CEBA"});
        data.add(new String[]{"6.6.5", "CEBAE1BDB9"});
        data.add(new String[]{"6.6.7", "CEBAE1BDB9CF83"});
        data.add(new String[]{"6.6.9", "CEBAE1BDB9CF83CEBC"});
        // - first possible sequence of a certain length (1 code point)
        data.add(new String[]{"6.7.1", "00"});
        data.add(new String[]{"6.7.2", "C280"});
        data.add(new String[]{"6.7.3", "E0A080"});
        data.add(new String[]{"6.7.4", "F0908080"});
        // - last possible sequence of a certain length (1 code point)
        data.add(new String[]{"6.9.1", "7F"});
        data.add(new String[]{"6.9.2", "DFBF"});
        data.add(new String[]{"6.9.3", "EFBFBF"});
        data.add(new String[]{"6.9.4", "F48FBFBF"});
        // - other boundary conditions
        data.add(new String[]{"6.11.1", "ED9FBF"});
        data.add(new String[]{"6.11.2", "EE8080"});
        data.add(new String[]{"6.11.3", "EFBFBD"});
        data.add(new String[]{"6.11.4", "F48FBFBF"});
        // - non character code points
        data.add(new String[]{"6.22.1", "EFBFBE"});
        data.add(new String[]{"6.22.2", "EFBFBF"});
        data.add(new String[]{"6.22.3", "F09FBFBE"});
        data.add(new String[]{"6.22.4", "F09FBFBF"});
        data.add(new String[]{"6.22.5", "F0AFBFBE"});
        data.add(new String[]{"6.22.6", "F0AFBFBF"});
        data.add(new String[]{"6.22.7", "F0BFBFBE"});
        data.add(new String[]{"6.22.8", "F0BFBFBF"});
        data.add(new String[]{"6.22.9", "F18FBFBE"});
        data.add(new String[]{"6.22.10", "F18FBFBF"});
        data.add(new String[]{"6.22.11", "F19FBFBE"});
        data.add(new String[]{"6.22.12", "F19FBFBF"});
        data.add(new String[]{"6.22.13", "F1AFBFBE"});
        data.add(new String[]{"6.22.14", "F1AFBFBF"});
        data.add(new String[]{"6.22.15", "F1BFBFBE"});
        data.add(new String[]{"6.22.16", "F1BFBFBF"});
        data.add(new String[]{"6.22.17", "F28FBFBE"});
        data.add(new String[]{"6.22.18", "F28FBFBF"});
        data.add(new String[]{"6.22.19", "F29FBFBE"});
        data.add(new String[]{"6.22.20", "F29FBFBF"});
        data.add(new String[]{"6.22.21", "F2AFBFBE"});
        data.add(new String[]{"6.22.22", "F2AFBFBF"});
        data.add(new String[]{"6.22.23", "F2BFBFBE"});
        data.add(new String[]{"6.22.24", "F2BFBFBF"});
        data.add(new String[]{"6.22.25", "F38FBFBE"});
        data.add(new String[]{"6.22.26", "F38FBFBF"});
        data.add(new String[]{"6.22.27", "F39FBFBE"});
        data.add(new String[]{"6.22.28", "F39FBFBF"});
        data.add(new String[]{"6.22.29", "F3AFBFBE"});
        data.add(new String[]{"6.22.30", "F3AFBFBF"});
        data.add(new String[]{"6.22.31", "F3BFBFBE"});
        data.add(new String[]{"6.22.32", "F3BFBFBF"});
        data.add(new String[]{"6.22.33", "F48FBFBE"});
        data.add(new String[]{"6.22.34", "F48FBFBF"});
        // - unicode replacement character
        data.add(new String[]{"6.23.1", "EFBFBD"});

        return data.stream().map(Arguments::of);
    }

    @ParameterizedTest
    @MethodSource("utfSequences")
    public void assertEchoTextMessage(String testId, String hexMsg) throws Exception
    {
        ByteBuffer msg = Hex.asByteBuffer(hexMsg);

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload(msg));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload(clone(msg)));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        try (Fuzzer fuzzer = new Fuzzer(this))
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
        }
    }
}
