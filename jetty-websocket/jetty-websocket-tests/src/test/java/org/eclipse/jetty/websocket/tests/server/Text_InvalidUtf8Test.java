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
import org.eclipse.jetty.toolchain.test.jupiter.TestTrackerExtension;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.tests.TestWebSocket;
import org.eclipse.jetty.websocket.tests.server.servlets.EchoSocket;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests of Known Bad UTF8 sequences that should trigger a {@link StatusCode#BAD_PAYLOAD} close and early connection termination
 */
@ExtendWith(TestTrackerExtension.class)
public class Text_InvalidUtf8Test extends AbstractLocalServerCase
{
    public static Stream<Arguments> data()
    {
        // The various Known Bad UTF8 sequences as a String (hex form)
        return Stream.of(
                // @formatter:off
                // - differently unicode fragmented
                Arguments.of("6.3.1", "CEBAE1BDB9CF83CEBCCEB5EDA080656469746564"),
                // - partial/incomplete multi-byte code points
                Arguments.of("6.6.1", "CE"),
                Arguments.of("6.6.3", "CEBAE1"),
                Arguments.of("6.6.4", "CEBAE1BD"),
                Arguments.of("6.6.6", "CEBAE1BDB9CF"),
                Arguments.of("6.6.8", "CEBAE1BDB9CF83CE"),
                Arguments.of("6.6.10", "CEBAE1BDB9CF83CEBCCE"),
                // - first possible sequence length 5/6 (invalid code points)
                Arguments.of("6.8.1", "F888808080"),
                Arguments.of("6.8.2", "FC8480808080"),
                // - last possible sequence length (invalid code points)
                Arguments.of("6.10.1", "F7BFBFBF"),
                Arguments.of("6.10.2", "FBBFBFBFBF"),
                Arguments.of("6.10.3", "FDBFBFBFBFBF"),
                // - other boundary conditions
                Arguments.of("6.11.5", "F4908080"),
                // - unexpected continuation bytes
                Arguments.of("6.12.1", "80"),
                Arguments.of("6.12.2", "BF"),
                Arguments.of("6.12.3", "80BF"),
                Arguments.of("6.12.4", "80BF80"),
                Arguments.of("6.12.5", "80BF80BF"),
                Arguments.of("6.12.6", "80BF80BF80"),
                Arguments.of("6.12.7", "80BF80BF80BF"),
                Arguments.of("6.12.8", "808182838485868788898A8B8C8D8E8F909192939495969798999A9B9C9D9E9"
                        + "FA0A1A2A3A4A5A6A7A8A9AAABACADAEAFB0B1B2B3B4B5B6B7B8B9BABBBCBDBE"),
                // - lonely start characters
                Arguments.of("6.13.1", "C020C120C220C320C420C520C620C720C820C920CA20CB20CC20CD20CE20CF2"
                        + "0D020D120D220D320D420D520D620D720D820D920DA20DB20DC20DD20DE20"),
                Arguments.of("6.13.2", "E020E120E220E320E420E520E620E720E820E920EA20EB20EC20ED20EE20"),
                Arguments.of("6.13.3", "F020F120F220F320F420F520F620"),
                Arguments.of("6.13.4", "F820F920FA20"),
                Arguments.of("6.13.5", "FC20"),
                // - sequences with last continuation byte missing
                Arguments.of("6.14.1", "C0"),
                Arguments.of("6.14.2", "E080"),
                Arguments.of("6.14.3", "F08080"),
                Arguments.of("6.14.4", "F8808080"),
                Arguments.of("6.14.5", "FC80808080"),
                Arguments.of("6.14.6", "DF"),
                Arguments.of("6.14.7", "EFBF"),
                Arguments.of("6.14.8", "F7BFBF"),
                Arguments.of("6.14.9", "FBBFBFBF"),
                Arguments.of("6.14.10", "FDBFBFBFBF"),
                // - concatenation of incomplete sequences
                Arguments.of("6.15.1", "C0E080F08080F8808080FC80808080DFEFBFF7BFBFFBBFBFBFFDBFBFBFBF"),
                // - impossible bytes
                Arguments.of("6.16.1", "FE"),
                Arguments.of("6.16.2", "FF"),
                Arguments.of("6.16.3", "FEFEFFFF"),
                // - overlong ascii characters
                Arguments.of("6.17.1", "C0AF"),
                Arguments.of("6.17.2", "E080AF"),
                Arguments.of("6.17.3", "F08080AF"),
                Arguments.of("6.17.4", "F8808080AF"),
                Arguments.of("6.17.5", "FC80808080AF"),
                // - maximum overlong sequences
                Arguments.of("6.18.1", "C1BF"),
                Arguments.of("6.18.2", "E09FBF"),
                Arguments.of("6.18.3", "F08FBFBF"),
                Arguments.of("6.18.4", "F887BFBFBF"),
                Arguments.of("6.18.5", "FC83BFBFBFBF"),
                // - overlong representation of NUL character
                Arguments.of("6.19.1", "C080"),
                Arguments.of("6.19.2", "E08080"),
                Arguments.of("6.19.3", "F0808080"),
                Arguments.of("6.19.4", "F880808080"),
                Arguments.of("6.19.5", "FC8080808080"),
                // - single UTF-16 surrogates
                Arguments.of("6.20.1", "EDA080"),
                Arguments.of("6.20.2", "EDADBF"),
                Arguments.of("6.20.3", "EDAE80"),
                Arguments.of("6.20.4", "EDAFBF"),
                Arguments.of("6.20.5", "EDB080"),
                Arguments.of("6.20.6", "EDBE80"),
                Arguments.of("6.20.7", "EDBFBF"),
                // - paired UTF-16 surrogates
                Arguments.of("6.21.1", "EDA080EDB080"),
                Arguments.of("6.21.2", "EDA080EDBFBF"),
                Arguments.of("6.21.3", "EDADBFEDB080"),
                Arguments.of("6.21.4", "EDADBFEDBFBF"),
                Arguments.of("6.21.5", "EDAE80EDB080"),
                Arguments.of("6.21.6", "EDAE80EDBFBF"),
                Arguments.of("6.21.7", "EDAFBFEDB080"),
                Arguments.of("6.21.8", "EDAFBFEDBFBF")
                // @formatter:on
        );
    }

    @ParameterizedTest(name = "{0} - {1}")
    @MethodSource("data")
    public void assertBadTextPayload(String testId, String hexMsg) throws Exception
    {
        LOG.debug("Test ID: {}", testId);
        byte[] invalid = Hex.asByteArray(hexMsg);

        List<Frame> send = new ArrayList<>();
        send.add(new Frame(OpCode.TEXT).setPayload(ByteBuffer.wrap(invalid)));
        send.add(CloseStatus.toFrame(StatusCode.NORMAL));

        List<Frame> expect = new ArrayList<>();
        expect.add(CloseStatus.toFrame(StatusCode.BAD_PAYLOAD));

        try (StacklessLogging ignored = new StacklessLogging(EchoSocket.class);
             TestWebSocket session = server.newClient())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
}
