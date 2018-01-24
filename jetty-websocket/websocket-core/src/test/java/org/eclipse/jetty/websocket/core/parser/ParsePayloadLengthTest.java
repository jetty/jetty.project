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

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.core.Generator;
import org.eclipse.jetty.websocket.core.Parser;
import org.eclipse.jetty.websocket.core.RawFrameBuilder;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.frames.OpCode;
import org.eclipse.jetty.websocket.core.frames.WebSocketFrame;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * Test behavior of Parser with payload length parsing (per RFC6455)
 */
@RunWith(Parameterized.class)
public class ParsePayloadLengthTest
{
    @Parameterized.Parameters(name = "size={0} {1}")
    public static List<Object[]> data()
    {
        List<Object[]> data = new ArrayList<>();
        // -- small 7-bit payload length format (RFC6455)
        data.add(new Object[]{0, "Autobahn Server Testcase 1.1.1"});
        data.add(new Object[]{1, "Jetty Testcase - 1B"});
        data.add(new Object[]{125, "Autobahn Server Testcase 1.1.2"});
        // -- medium 2 byte payload length format
        data.add(new Object[]{126, "Autobahn Server Testcase 1.1.3"});
        data.add(new Object[]{127, "Autobahn Server Testcase 1.1.4"});
        data.add(new Object[]{128, "Autobahn Server Testcase 1.1.5"});
        data.add(new Object[]{65535, "Autobahn Server Testcase 1.1.6"});
        // -- large 8 byte payload length
        data.add(new Object[]{65536, "Autobahn Server Testcase 1.1.7"});
        data.add(new Object[]{500 * 1024, "Jetty Testcase - 500KB"});
        data.add(new Object[]{10 * 1024 * 1024, "Jetty Testcase - 10MB"});

        return data;
    }

    @Parameterized.Parameter(0)
    public int size;

    @Parameterized.Parameter(1)
    public String description;

    private WebSocketPolicy policy = WebSocketPolicy.newClientPolicy();
    private ByteBufferPool bufferPool = new MappedByteBufferPool();

    @Test
    public void testPayloadLength() throws InterruptedException
    {
        ParserCapture capture = new ParserCapture();
        Parser parser = new Parser(policy, bufferPool, capture);

        ByteBuffer raw = BufferUtil.allocate(size + Generator.MAX_HEADER_LENGTH);
        BufferUtil.clearToFill(raw);

        // Create text frame
        RawFrameBuilder.putOpFin(raw, OpCode.TEXT, true);
        RawFrameBuilder.putLength(raw, size, false); // len of closeCode
        byte payload[] = new byte[size];
        Arrays.fill(payload, (byte) 'x');
        raw.put(payload);

        // parse buffer
        BufferUtil.flipToFlush(raw, 0);
        parser.parse(raw);

        // validate frame
        WebSocketFrame frame = capture.framesQueue.poll(1, TimeUnit.SECONDS);
        assertThat("Frame opcode", frame.getOpCode(), is(OpCode.TEXT));
        assertThat("Frame payloadLength", frame.getPayloadLength(), is(size));
        if (size > 0)
            assertThat("Frame payload.remaining", frame.getPayload().remaining(), is(size));
        else
            assertThat("Frame payload", frame.getPayload(), nullValue());
    }
}
