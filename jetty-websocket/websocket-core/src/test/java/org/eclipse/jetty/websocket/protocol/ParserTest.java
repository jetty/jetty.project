// ========================================================================
// Copyright 2011-2012 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
//     The Eclipse Public License is available at
//     http://www.eclipse.org/legal/epl-v10.html
//
//     The Apache License v2.0 is available at
//     http://www.opensource.org/licenses/apache2.0.php
//
// You may elect to redistribute this code under either of these licenses.
//========================================================================
package org.eclipse.jetty.websocket.protocol;

import static org.hamcrest.Matchers.is;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.junit.Assert;
import org.junit.Test;

public class ParserTest
{
    /**
     * Similar to the server side 5.15 testcase. A normal 2 fragment text text message, followed by another continuation.
     */
    @Test
    public void testParseCase5_15()
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new WebSocketFrame(OpCode.TEXT).setPayload("fragment1").setFin(false));
        send.add(new WebSocketFrame(OpCode.CONTINUATION).setPayload("fragment2").setFin(true));
        send.add(new WebSocketFrame(OpCode.CONTINUATION).setPayload("fragment3").setFin(false)); // bad frame
        send.add(new WebSocketFrame(OpCode.TEXT).setPayload("fragment4").setFin(true));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        ByteBuffer completeBuf = UnitGenerator.generate(send);
        UnitParser parser = new UnitParser();
        IncomingFramesCapture capture = new IncomingFramesCapture();
        parser.setIncomingFramesHandler(capture);
        parser.parse(completeBuf);

        capture.assertErrorCount(1);
        capture.assertHasFrame(OpCode.TEXT,2);
    }

    /**
     * Similar to the server side 5.18 testcase. Text message fragmented as 2 frames, both as opcode=TEXT
     */
    @Test
    public void testParseCase5_18()
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new WebSocketFrame(OpCode.TEXT).setPayload("fragment1").setFin(false));
        send.add(new WebSocketFrame(OpCode.TEXT).setPayload("fragment2").setFin(true)); // bad frame, must be continuation
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        ByteBuffer completeBuf = UnitGenerator.generate(send);
        UnitParser parser = new UnitParser();
        IncomingFramesCapture capture = new IncomingFramesCapture();
        parser.setIncomingFramesHandler(capture);
        parser.parse(completeBuf);

        capture.assertErrorCount(1);
        capture.assertHasFrame(OpCode.TEXT,1); // fragment 1
    }

    /**
     * Similar to the server side 5.19 testcase.
     * text message, send in 5 frames/fragments, with 2 pings in the mix.
     */
    @Test
    public void testParseCase5_19()
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new WebSocketFrame(OpCode.TEXT).setPayload("f1").setFin(false));
        send.add(new WebSocketFrame(OpCode.CONTINUATION).setPayload(",f2").setFin(false));
        send.add(new WebSocketFrame(OpCode.PING).setPayload("pong-1"));
        send.add(new WebSocketFrame(OpCode.CONTINUATION).setPayload(",f3").setFin(false));
        send.add(new WebSocketFrame(OpCode.CONTINUATION).setPayload(",f4").setFin(false));
        send.add(new WebSocketFrame(OpCode.PING).setPayload("pong-2"));
        send.add(new WebSocketFrame(OpCode.CONTINUATION).setPayload(",f5").setFin(true));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        ByteBuffer completeBuf = UnitGenerator.generate(send);
        UnitParser parser = new UnitParser();
        IncomingFramesCapture capture = new IncomingFramesCapture();
        parser.setIncomingFramesHandler(capture);
        parser.parse(completeBuf);

        capture.assertErrorCount(0);
        capture.assertHasFrame(OpCode.TEXT,5);
        capture.assertHasFrame(OpCode.CLOSE,1);
        capture.assertHasFrame(OpCode.PING,2);
    }

    /**
     * Similar to the server side 5.6 testcase. pong, then text, then close frames.
     */
    @Test
    public void testParseCase5_6()
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(WebSocketFrame.pong().setPayload("ping"));
        send.add(WebSocketFrame.text("hello, world"));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        ByteBuffer completeBuf = UnitGenerator.generate(send);
        UnitParser parser = new UnitParser();
        IncomingFramesCapture capture = new IncomingFramesCapture();
        parser.setIncomingFramesHandler(capture);
        parser.parse(completeBuf);

        capture.assertErrorCount(0);
        capture.assertHasFrame(OpCode.TEXT,1);
        capture.assertHasFrame(OpCode.CLOSE,1);
        capture.assertHasFrame(OpCode.PONG,1);
    }

    /**
     * Similar to the server side 6.2.3 testcase. Lots of small 1 byte UTF8 Text frames, representing 1 overall text message.
     */
    @Test
    public void testParseCase6_2_3()
    {
        String utf8 = "Hello-\uC2B5@\uC39F\uC3A4\uC3BC\uC3A0\uC3A1-UTF-8!!";
        byte msg[] = StringUtil.getUtf8Bytes(utf8);

        List<WebSocketFrame> send = new ArrayList<>();
        int len = msg.length;
        byte opcode = OpCode.TEXT;
        byte mini[];
        for (int i = 0; i < len; i++)
        {
            WebSocketFrame frame = new WebSocketFrame(opcode);
            mini = new byte[1];
            mini[0] = msg[i];
            frame.setPayload(mini);
            boolean isLast = (i >= (len - 1));
            frame.setFin(isLast);
            send.add(frame);
            opcode = OpCode.CONTINUATION;
        }
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        ByteBuffer completeBuf = UnitGenerator.generate(send);
        UnitParser parser = new UnitParser();
        IncomingFramesCapture capture = new IncomingFramesCapture();
        parser.setIncomingFramesHandler(capture);
        parser.parse(completeBuf);

        capture.assertErrorCount(0);
        capture.assertHasFrame(OpCode.TEXT,len);
        capture.assertHasFrame(OpCode.CLOSE,1);
    }

    @Test
    public void testParseNothing()
    {
        ByteBuffer buf = ByteBuffer.allocate(16);
        // Put nothing in the buffer.
        buf.flip();

        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER);
        Parser parser = new Parser(policy);
        IncomingFramesCapture capture = new IncomingFramesCapture();
        parser.setIncomingFramesHandler(capture);
        parser.parse(buf);

        capture.assertNoErrors();
        Assert.assertThat("Frame Count",capture.getFrames().size(),is(0));
    }
}
