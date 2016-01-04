//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
import java.util.Collection;
import java.util.List;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.Parser;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.common.test.Fuzzer;
import org.eclipse.jetty.websocket.common.util.Hex;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/**
 * Tests of Known Bad UTF8 sequences that should trigger a {@link StatusCode#BAD_PAYLOAD} close and early connection termination
 */
@RunWith(Parameterized.class)
public class TestABCase6_BadUTF extends AbstractABCase
{
    private static final Logger LOG = Log.getLogger(TestABCase6_BadUTF.class);

    @Parameters
    public static Collection<String[]> data()
    {
        // The various Good UTF8 sequences as a String (hex form)
        List<String[]> data = new ArrayList<>();

        // @formatter:off
        // - differently unicode fragmented
        data.add(new String[]{ "6.3.1", "CEBAE1BDB9CF83CEBCCEB5EDA080656469746564" });
        // - partial/incomplete multi-byte code points
        data.add(new String[]{ "6.6.1", "CE" });
        data.add(new String[]{ "6.6.3", "CEBAE1" });
        data.add(new String[]{ "6.6.4", "CEBAE1BD" });
        data.add(new String[]{ "6.6.6", "CEBAE1BDB9CF" });
        data.add(new String[]{ "6.6.8", "CEBAE1BDB9CF83CE" });
        data.add(new String[]{ "6.6.10", "CEBAE1BDB9CF83CEBCCE" });
        // - first possible sequence length 5/6 (invalid code points)
        data.add(new String[]{ "6.8.1", "F888808080" });
        data.add(new String[]{ "6.8.2", "FC8480808080" });
        // - last possible sequence length (invalid code points)
        data.add(new String[]{ "6.10.1", "F7BFBFBF" });
        data.add(new String[]{ "6.10.2", "FBBFBFBFBF" });
        data.add(new String[]{ "6.10.3", "FDBFBFBFBFBF" });
        // - other boundary conditions
        data.add(new String[]{ "6.11.5", "F4908080" });
        // - unexpected continuation bytes
        data.add(new String[]{ "6.12.1", "80" });
        data.add(new String[]{ "6.12.2", "BF" });
        data.add(new String[]{ "6.12.3", "80BF" });
        data.add(new String[]{ "6.12.4", "80BF80" });
        data.add(new String[]{ "6.12.5", "80BF80BF" });
        data.add(new String[]{ "6.12.6", "80BF80BF80" });
        data.add(new String[]{ "6.12.7", "80BF80BF80BF" });
        data.add(new String[]{ "6.12.8", "808182838485868788898A8B8C8D8E8F909192939495969798999A9B9C9D9E9"
                + "FA0A1A2A3A4A5A6A7A8A9AAABACADAEAFB0B1B2B3B4B5B6B7B8B9BABBBCBDBE" });
        // - lonely start characters
        data.add(new String[]{ "6.13.1", "C020C120C220C320C420C520C620C720C820C920CA20CB20CC20CD20CE20CF2"
                + "0D020D120D220D320D420D520D620D720D820D920DA20DB20DC20DD20DE20" });
        data.add(new String[]{ "6.13.2", "E020E120E220E320E420E520E620E720E820E920EA20EB20EC20ED20EE20" });
        data.add(new String[]{ "6.13.3", "F020F120F220F320F420F520F620" });
        data.add(new String[]{ "6.13.4", "F820F920FA20" });
        data.add(new String[]{ "6.13.5", "FC20" });
        // - sequences with last continuation byte missing
        data.add(new String[]{ "6.14.1", "C0" });
        data.add(new String[]{ "6.14.2", "E080" });
        data.add(new String[]{ "6.14.3", "F08080" });
        data.add(new String[]{ "6.14.4", "F8808080" });
        data.add(new String[]{ "6.14.5", "FC80808080" });
        data.add(new String[]{ "6.14.6", "DF" });
        data.add(new String[]{ "6.14.7", "EFBF" });
        data.add(new String[]{ "6.14.8", "F7BFBF" });
        data.add(new String[]{ "6.14.9", "FBBFBFBF" });
        data.add(new String[]{ "6.14.10", "FDBFBFBFBF" });
        // - concatenation of incomplete sequences
        data.add(new String[]{ "6.15.1", "C0E080F08080F8808080FC80808080DFEFBFF7BFBFFBBFBFBFFDBFBFBFBF" });
        // - impossible bytes
        data.add(new String[]{ "6.16.1", "FE" });
        data.add(new String[]{ "6.16.2", "FF" });
        data.add(new String[]{ "6.16.3", "FEFEFFFF" });
        // - overlong ascii characters
        data.add(new String[]{ "6.17.1", "C0AF" });
        data.add(new String[]{ "6.17.2", "E080AF" });
        data.add(new String[]{ "6.17.3", "F08080AF" });
        data.add(new String[]{ "6.17.4", "F8808080AF" });
        data.add(new String[]{ "6.17.5", "FC80808080AF" });
        // - maximum overlong sequences
        data.add(new String[]{ "6.18.1", "C1BF" });
        data.add(new String[]{ "6.18.2", "E09FBF" });
        data.add(new String[]{ "6.18.3", "F08FBFBF" });
        data.add(new String[]{ "6.18.4", "F887BFBFBF" });
        data.add(new String[]{ "6.18.5", "FC83BFBFBFBF" });
        // - overlong representation of NUL character
        data.add(new String[]{ "6.19.1", "C080" });
        data.add(new String[]{ "6.19.2", "E08080" });
        data.add(new String[]{ "6.19.3", "F0808080" });
        data.add(new String[]{ "6.19.4", "F880808080" });
        data.add(new String[]{ "6.19.5", "FC8080808080" });
        // - single UTF-16 surrogates
        data.add(new String[]{ "6.20.1", "EDA080" });
        data.add(new String[]{ "6.20.2", "EDADBF" });
        data.add(new String[]{ "6.20.3", "EDAE80" });
        data.add(new String[]{ "6.20.4", "EDAFBF" });
        data.add(new String[]{ "6.20.5", "EDB080" });
        data.add(new String[]{ "6.20.6", "EDBE80" });
        data.add(new String[]{ "6.20.7", "EDBFBF" });
        // - paired UTF-16 surrogates
        data.add(new String[]{ "6.21.1", "EDA080EDB080" });
        data.add(new String[]{ "6.21.2", "EDA080EDBFBF" });
        data.add(new String[]{ "6.21.3", "EDADBFEDB080" });
        data.add(new String[]{ "6.21.4", "EDADBFEDBFBF" });
        data.add(new String[]{ "6.21.5", "EDAE80EDB080" });
        data.add(new String[]{ "6.21.6", "EDAE80EDBFBF" });
        data.add(new String[]{ "6.21.7", "EDAFBFEDB080" });
        data.add(new String[]{ "6.21.8", "EDAFBFEDBFBF" });
        // @formatter:on

        return data;
    }

    private final byte[] invalid;

    public TestABCase6_BadUTF(String testId, String hexMsg)
    {
        LOG.debug("Test ID: {}",testId);
        this.invalid = Hex.asByteArray(hexMsg);
    }

    @Test
    public void assertBadTextPayload() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload(ByteBuffer.wrap(invalid)));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseInfo(StatusCode.BAD_PAYLOAD).asFrame());

        try (Fuzzer fuzzer = new Fuzzer(this))
        {
            try (StacklessLogging supress = new StacklessLogging(Parser.class))
            {
                fuzzer.connect();
                fuzzer.setSendMode(Fuzzer.SendMode.BULK);
                fuzzer.send(send);
                fuzzer.expect(expect);
            }
        }
    }
}
