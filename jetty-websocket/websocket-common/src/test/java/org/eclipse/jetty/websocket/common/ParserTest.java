//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.common;

import static org.hamcrest.Matchers.*;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.BadPayloadException;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.junit.Assert;
import org.junit.Test;

public class ParserTest
{
    private static final Logger LOG = Log.getLogger(ParserTest.class);

    /** Parse, but be quiet about stack traces */
    private void parseQuietly(UnitParser parser, ByteBuffer buf)
    {
        LogShush.disableStacks(Parser.class);
        try {
            parser.parse(buf);
        } finally {
            LogShush.enableStacks(Parser.class);
        }
    }

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

        parseQuietly(parser,completeBuf);

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
        parseQuietly(parser,completeBuf);

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
        parseQuietly(parser,completeBuf);

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

    /**
     * Similar to the server side 6.4.3 testcase.
     */
    @Test
    public void testParseCase6_4_3()
    {
        ByteBuffer payload = ByteBuffer.allocate(64);
        BufferUtil.clearToFill(payload);
        payload.put(TypeUtil.fromHexString("cebae1bdb9cf83cebcceb5")); // good
        payload.put(TypeUtil.fromHexString("f4908080")); // INVALID
        payload.put(TypeUtil.fromHexString("656469746564")); // good
        BufferUtil.flipToFlush(payload,0);

        WebSocketFrame text = new WebSocketFrame();
        text.setMask(TypeUtil.fromHexString("11223344"));
        text.setPayload(payload);
        text.setOpCode(OpCode.TEXT);

        ByteBuffer buf = new UnitGenerator().generate(text);

        ByteBuffer part1 = ByteBuffer.allocate(17); // header + good
        ByteBuffer part2 = ByteBuffer.allocate(4); // invalid
        ByteBuffer part3 = ByteBuffer.allocate(10); // the rest (all good utf)

        BufferUtil.put(buf,part1);
        BufferUtil.put(buf,part2);
        BufferUtil.put(buf,part3);

        BufferUtil.flipToFlush(part1,0);
        BufferUtil.flipToFlush(part2,0);
        BufferUtil.flipToFlush(part3,0);

        LOG.debug("Part1: {}",BufferUtil.toDetailString(part1));
        LOG.debug("Part2: {}",BufferUtil.toDetailString(part2));
        LOG.debug("Part3: {}",BufferUtil.toDetailString(part3));

        UnitParser parser = new UnitParser();
        IncomingFramesCapture capture = new IncomingFramesCapture();
        parser.setIncomingFramesHandler(capture);

        parseQuietly(parser,part1);
        capture.assertErrorCount(0);
        parseQuietly(parser,part2);
        capture.assertErrorCount(1);
        capture.assertHasErrors(BadPayloadException.class,1);
    }

    @Test
    public void testParseNothing()
    {
        ByteBuffer buf = ByteBuffer.allocate(16);
        // Put nothing in the buffer.
        buf.flip();

        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER);
        Parser parser = new UnitParser(policy);
        IncomingFramesCapture capture = new IncomingFramesCapture();
        parser.setIncomingFramesHandler(capture);
        parser.parse(buf);

        capture.assertNoErrors();
        Assert.assertThat("Frame Count",capture.getFrames().size(),is(0));
    }
}
