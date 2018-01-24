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
 * Test behavior of Parser when encountering bad / forbidden opcodes (per RFC6455)
 */
@RunWith(Parameterized.class)
public class ParserBadOpCodesTest
{
    @Parameterized.Parameters(name = "opcode={0} {1}")
    public static List<Object[]> data()
    {
        List<Object[]> data = new ArrayList<>();
        data.add(new Object[]{(byte) 3, "Autobahn Server Testcase 4.1.1"});
        data.add(new Object[]{(byte) 4, "Autobahn Server Testcase 4.1.2"});
        data.add(new Object[]{(byte) 5, "Autobahn Server Testcase 4.1.3"});
        data.add(new Object[]{(byte) 6, "Autobahn Server Testcase 4.1.4"});
        data.add(new Object[]{(byte) 7, "Autobahn Server Testcase 4.1.5"});
        data.add(new Object[]{(byte) 11, "Autobahn Server Testcase 4.2.1"});
        data.add(new Object[]{(byte) 12, "Autobahn Server Testcase 4.2.2"});
        data.add(new Object[]{(byte) 13, "Autobahn Server Testcase 4.2.3"});
        data.add(new Object[]{(byte) 14, "Autobahn Server Testcase 4.2.4"});
        data.add(new Object[]{(byte) 15, "Autobahn Server Testcase 4.2.5"});

        return data;
    }

    @Rule
    public ExpectedException expectedException = ExpectedException.none();

    @Parameterized.Parameter(0)
    public byte opcode;

    @Parameterized.Parameter(1)
    public String description;

    private WebSocketPolicy policy = WebSocketPolicy.newClientPolicy();
    private ByteBufferPool bufferPool = new MappedByteBufferPool();

    @Test
    public void testBadOpCode()
    {
        ParserCapture capture = new ParserCapture();
        Parser parser = new Parser(policy, bufferPool, capture);

        ByteBuffer raw = BufferUtil.allocate(256);
        BufferUtil.flipToFill(raw);

        // add bad opcode frame
        RawFrameBuilder.putOpFin(raw, opcode, true);
        RawFrameBuilder.putLength(raw, 0, false);

        // parse buffer
        BufferUtil.flipToFlush(raw, 0);
        try (StacklessLogging ignore = new StacklessLogging(Parser.class))
        {
            expectedException.expect(ProtocolException.class);
            expectedException.expectMessage(containsString("Unknown opcode: " + opcode));
            parser.parse(raw);
        }
    }

    @Test
    public void testText_BadOpCode_Ping()
    {
        ParserCapture capture = new ParserCapture();
        Parser parser = new Parser(policy, bufferPool, capture);

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
            expectedException.expect(ProtocolException.class);
            expectedException.expectMessage(containsString("Unknown opcode: " + opcode));
            parser.parse(raw);
        }
    }
}
