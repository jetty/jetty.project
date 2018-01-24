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
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Parser;
import org.eclipse.jetty.websocket.core.RawFrameBuilder;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.frames.CloseFrame;
import org.eclipse.jetty.websocket.core.frames.OpCode;
import org.eclipse.jetty.websocket.core.frames.WebSocketFrame;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * Test behavior of Parser when encountering good / valid close status codes (per RFC6455)
 */
@RunWith(Parameterized.class)
public class ParserGoodCloseStatusCodesTest
{
    @Parameterized.Parameters(name = "closeCode={0} {1}")
    public static List<Object[]> data()
    {
        List<Object[]> data = new ArrayList<>();
        data.add(new Object[]{1000, "Autobahn Server Testcase 7.7.1"});
        data.add(new Object[]{1001, "Autobahn Server Testcase 7.7.2"});
        data.add(new Object[]{1002, "Autobahn Server Testcase 7.7.3"});
        data.add(new Object[]{1003, "Autobahn Server Testcase 7.7.4"});
        data.add(new Object[]{1007, "Autobahn Server Testcase 7.7.5"});
        data.add(new Object[]{1008, "Autobahn Server Testcase 7.7.6"});
        data.add(new Object[]{1009, "Autobahn Server Testcase 7.7.7"});
        data.add(new Object[]{1010, "Autobahn Server Testcase 7.7.8"});
        data.add(new Object[]{1011, "Autobahn Server Testcase 7.7.9"});
        // These must be allowed, and cannot result in a ProtocolException
        data.add(new Object[]{1012, "IANA Assigned"}); // Now IANA Assigned
        data.add(new Object[]{1013, "IANA Assigned"}); // Now IANA Assigned
        data.add(new Object[]{1014, "IANA Assigned"}); // Now IANA Assigned
        data.add(new Object[]{3000, "Autobahn Server Testcase 7.7.10"});
        data.add(new Object[]{3099, "Autobahn Server Testcase 7.7.11"});
        data.add(new Object[]{4000, "Autobahn Server Testcase 7.7.12"});
        data.add(new Object[]{4099, "Autobahn Server Testcase 7.7.13"});

        return data;
    }

    @Parameterized.Parameter(0)
    public int closeCode;

    @Parameterized.Parameter(1)
    public String description;

    private WebSocketPolicy policy = WebSocketPolicy.newClientPolicy();
    private ByteBufferPool bufferPool = new MappedByteBufferPool();

    @Test
    public void testGoodCloseCode() throws InterruptedException
    {
        ParserCapture capture = new ParserCapture();
        Parser parser = new Parser(policy, bufferPool, capture);

        ByteBuffer raw = BufferUtil.allocate(256);
        BufferUtil.clearToFill(raw);

        // add close frame
        RawFrameBuilder.putOpFin(raw, OpCode.CLOSE, true);
        RawFrameBuilder.putLength(raw, 2, false); // len of closeCode
        raw.putChar((char) closeCode); // 2 bytes for closeCode

        // parse buffer
        BufferUtil.flipToFlush(raw, 0);
        parser.parse(raw);
        WebSocketFrame frame = capture.framesQueue.poll(1, TimeUnit.SECONDS);
        assertThat("Frame opcode", frame.getOpCode(), is(OpCode.CLOSE));
        CloseStatus closeStatus = CloseFrame.toCloseStatus(frame.getPayload());
        assertThat("CloseStatus.code", closeStatus.getCode(), is(closeCode));
        assertThat("CloseStatus.reason", closeStatus.getReason(), nullValue());
    }

    @Test
    public void testGoodCloseCode_WithReasonPhrase() throws InterruptedException
    {
        ParserCapture capture = new ParserCapture();
        Parser parser = new Parser(policy, bufferPool, capture);

        ByteBuffer raw = BufferUtil.allocate(256);
        BufferUtil.clearToFill(raw);

        // add close frame
        RawFrameBuilder.putOpFin(raw, OpCode.CLOSE, true);
        RawFrameBuilder.putLength(raw, 2 + 5, false); // len of closeCode + reason phrase
        raw.putChar((char) closeCode); // 2 bytes for closeCode
        raw.put("hello".getBytes(UTF_8));

        // parse buffer
        BufferUtil.flipToFlush(raw, 0);
        parser.parse(raw);
        WebSocketFrame frame = capture.framesQueue.poll(1, TimeUnit.SECONDS);
        assertThat("Frame opcode", frame.getOpCode(), is(OpCode.CLOSE));
        CloseStatus closeStatus = CloseFrame.toCloseStatus(frame.getPayload());
        assertThat("CloseStatus.code", closeStatus.getCode(), is(closeCode));
        assertThat("CloseStatus.reason", closeStatus.getReason(), is("hello"));
    }
}
