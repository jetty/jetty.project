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

package org.eclipse.jetty.websocket.core.parser;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.Matchers.containsString;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.websocket.core.Parser;
import org.eclipse.jetty.websocket.core.ProtocolException;
import org.eclipse.jetty.websocket.core.RawFrameBuilder;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.frames.OpCode;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * Test behavior of Parser when encountering bad / forbidden close status codes (per RFC6455)
 */
@RunWith(Parameterized.class)
public class ParserBadCloseStatusCodesTest
{
    @Parameterized.Parameters(name = "closeCode={0} {1}")
    public static List<Object[]> data()
    {
        List<Object[]> data = new ArrayList<>();
        data.add(new Object[]{ 0, "Autobahn Server Testcase 7.9.1"});
        data.add(new Object[]{ 999, "Autobahn Server Testcase 7.9.2"});
        data.add(new Object[]{ 1004, "Autobahn Server Testcase 7.9.3"}); // RFC6455/UNDEFINED
        data.add(new Object[]{ 1005, "Autobahn Server Testcase 7.9.4"}); // RFC6455/Cannot Be Transmitted
        data.add(new Object[]{ 1006, "Autobahn Server Testcase 7.9.5"}); // RFC6455/Cannot Be Transmitted
        // Leaving these 3 here and commented out so they don't get re-added (because they are missing)
        // See ParserGoodCloseStatusCodesTest for new test of these
        // data.add(new Object[]{ 1012, "Autobahn Server Testcase 7.9.6"}); // Now IANA Defined
        // data.add(new Object[]{ 1013, "Autobahn Server Testcase 7.9.7"}); // Now IANA Defined
        // data.add(new Object[]{ 1014, "Autobahn Server Testcase 7.9.8"}); // Now IANA Defined
        data.add(new Object[]{ 1015, "Autobahn Server Testcase 7.9.9"}); // RFC6455/Cannot Be Transmitted
        data.add(new Object[]{ 1016, "Autobahn Server Testcase 7.9.10"}); // RFC6455
        data.add(new Object[]{ 1100, "Autobahn Server Testcase 7.9.11"}); // RFC6455
        data.add(new Object[]{ 2000, "Autobahn Server Testcase 7.9.12"}); // RFC6455
        data.add(new Object[]{ 2999, "Autobahn Server Testcase 7.9.13"}); // RFC6455
        // -- close status codes, with undefined events in spec
        data.add(new Object[]{ 5000, "Autobahn Server Testcase 7.13.1"}); // RFC6455/Undefined
        data.add(new Object[]{ 65535, "Autobahn Server Testcase 7.13.2"}); // RFC6455/Undefined

        return data;
    }

    @Rule
    public ExpectedException expectedException = ExpectedException.none();

    @Parameterized.Parameter(0)
    public int closeCode;

    @Parameterized.Parameter(1)
    public String description;

    private WebSocketPolicy policy = WebSocketPolicy.newClientPolicy();
    private ByteBufferPool bufferPool = new MappedByteBufferPool();

    @Test
    public void testBadStatusCode()
    {
        ParserCapture capture = new ParserCapture();
        Parser parser = new Parser(policy, bufferPool, capture);

        ByteBuffer raw = BufferUtil.allocate(256);
        BufferUtil.clearToFill(raw);

        // add close frame
        RawFrameBuilder.putOpFin(raw, OpCode.CLOSE, true);
        RawFrameBuilder.putLength(raw, 2, false); // len of closeCode
        raw.putShort((short) closeCode); // 2 bytes for closeCode

        // parse buffer
        BufferUtil.flipToFlush(raw, 0);
        try (StacklessLogging ignore = new StacklessLogging(Parser.class))
        {
            expectedException.expect(ProtocolException.class);
            expectedException.expectMessage(containsString("Invalid Close Code: " + closeCode));
            parser.parse(raw);
        }
    }

    @Test
    public void testBadStatusCode_WithReasonPhrase()
    {
        ParserCapture capture = new ParserCapture();
        Parser parser = new Parser(policy, bufferPool, capture);

        ByteBuffer raw = BufferUtil.allocate(256);
        BufferUtil.clearToFill(raw);

        // add close frame
        RawFrameBuilder.putOpFin(raw, OpCode.CLOSE, true);
        RawFrameBuilder.putLength(raw, 2 + 5, false); // len of closeCode + reason phrase
        raw.putShort((short) closeCode); // 2 bytes for closeCode
        raw.put("hello".getBytes(UTF_8));

        // parse buffer
        BufferUtil.flipToFlush(raw, 0);
        try (StacklessLogging ignore = new StacklessLogging(Parser.class))
        {
            expectedException.expect(ProtocolException.class);
            expectedException.expectMessage(containsString("Invalid Close Code: " + closeCode));
            parser.parse(raw);
        }
    }
}
