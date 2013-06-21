//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.toolchain.test.AdvancedRunner;
import org.eclipse.jetty.toolchain.test.annotation.Slow;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.Parser;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.server.helper.Hex;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * UTF-8 Tests
 */
@RunWith(AdvancedRunner.class)
public class TestABCase6 extends AbstractABCase
{
    /**
     * Split a message byte array into a series of fragments (frames + continuations) of 1 byte message contents each.
     */
    protected void fragmentText(List<WebSocketFrame> frames, byte msg[])
    {
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
            frames.add(frame);
            opcode = OpCode.CONTINUATION;
        }
    }

    /**
     * text message, 1 frame, 0 length
     */
    @Test
    public void testCase6_1_1() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(WebSocketFrame.text());
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(WebSocketFrame.text());
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        Fuzzer fuzzer = new Fuzzer(this);
        try
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
        }
        finally
        {
            fuzzer.close();
        }
    }

    /**
     * text message, 0 length, 3 fragments
     */
    @Test
    public void testCase6_1_2() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new WebSocketFrame(OpCode.TEXT).setFin(false));
        send.add(new WebSocketFrame(OpCode.CONTINUATION).setFin(false));
        send.add(new WebSocketFrame(OpCode.CONTINUATION).setFin(true));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(WebSocketFrame.text());
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        Fuzzer fuzzer = new Fuzzer(this);
        try
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
        }
        finally
        {
            fuzzer.close();
        }
    }

    /**
     * text message, small length, 3 fragments (only middle frame has payload)
     */
    @Test
    public void testCase6_1_3() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new WebSocketFrame(OpCode.TEXT).setFin(false));
        send.add(new WebSocketFrame(OpCode.CONTINUATION).setFin(false).setPayload("middle"));
        send.add(new WebSocketFrame(OpCode.CONTINUATION).setFin(true));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(WebSocketFrame.text("middle"));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        Fuzzer fuzzer = new Fuzzer(this);
        try
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
        }
        finally
        {
            fuzzer.close();
        }
    }

    /**
     * valid utf8 text message, 2 fragments (on UTF8 code point boundary)
     */
    @Test
    public void testCase6_2_2() throws Exception
    {
        String utf8[] =
        { "Hello-\uC2B5@\uC39F\uC3A4", "\uC3BC\uC3A0\uC3A1-UTF-8!!" };

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new WebSocketFrame(OpCode.TEXT).setPayload(utf8[0]).setFin(false));
        send.add(new WebSocketFrame(OpCode.CONTINUATION).setPayload(utf8[1]).setFin(true));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new WebSocketFrame(OpCode.TEXT).setPayload(utf8[0] + utf8[1]));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        Fuzzer fuzzer = new Fuzzer(this);
        try
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
        }
        finally
        {
            fuzzer.close();
        }
    }

    /**
     * valid utf8 text message, many fragments (1 byte each)
     */
    @Test
    public void testCase6_2_3() throws Exception
    {
        String utf8 = "Hello-\uC2B5@\uC39F\uC3A4\uC3BC\uC3A0\uC3A1-UTF-8!!";
        byte msg[] = StringUtil.getUtf8Bytes(utf8);

        List<WebSocketFrame> send = new ArrayList<>();
        fragmentText(send,msg);
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new WebSocketFrame(OpCode.TEXT).setPayload(msg));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        Fuzzer fuzzer = new Fuzzer(this);
        try
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
        }
        finally
        {
            fuzzer.close();
        }
    }

    /**
     * valid utf8 text message, many fragments (1 byte each)
     */
    @Test
    public void testCase6_2_4() throws Exception
    {
        byte msg[] = Hex.asByteArray("CEBAE1BDB9CF83CEBCCEB5");

        List<WebSocketFrame> send = new ArrayList<>();
        fragmentText(send,msg);
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new WebSocketFrame(OpCode.TEXT).setPayload(msg));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        Fuzzer fuzzer = new Fuzzer(this);
        try
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
        }
        finally
        {
            fuzzer.close();
        }
    }

    /**
     * invalid utf8 text message, many fragments (1 byte each)
     */
    @Test
    public void testCase6_3_2() throws Exception
    {
        byte invalid[] = Hex.asByteArray("CEBAE1BDB9CF83CEBCCEB5EDA080656469746564");

        List<WebSocketFrame> send = new ArrayList<>();
        fragmentText(send,invalid);
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseInfo(StatusCode.BAD_PAYLOAD).asFrame());

        Fuzzer fuzzer = new Fuzzer(this);
        try
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
        }
        finally
        {
            fuzzer.close();
        }
    }

    /**
     * invalid text message, 3 fragments.
     * <p>
     * fragment #1 and fragment #3 are both valid in themselves.
     * <p>
     * fragment #2 contains the invalid utf8 code point.
     */
    @Test
    @Slow
    public void testCase6_4_1() throws Exception
    {
        byte part1[] = StringUtil.getUtf8Bytes("\u03BA\u1F79\u03C3\u03BC\u03B5");
        byte part2[] = Hex.asByteArray("F4908080"); // invalid
        byte part3[] = StringUtil.getUtf8Bytes("edited");

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseInfo(StatusCode.BAD_PAYLOAD).asFrame());

        Fuzzer fuzzer = new Fuzzer(this);
        try
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);

            fuzzer.send(new WebSocketFrame(OpCode.TEXT).setPayload(part1).setFin(false));
            TimeUnit.SECONDS.sleep(1);
            fuzzer.send(new WebSocketFrame(OpCode.CONTINUATION).setPayload(part2).setFin(false));
            TimeUnit.SECONDS.sleep(1);
            fuzzer.send(new WebSocketFrame(OpCode.CONTINUATION).setPayload(part3).setFin(true));

            fuzzer.expect(expect);
        }
        finally
        {
            fuzzer.close();
        }
    }

    /**
     * invalid text message, 3 fragments.
     * <p>
     * fragment #1 is valid and ends in the middle of an incomplete code point.
     * <p>
     * fragment #2 finishes the UTF8 code point but it is invalid
     * <p>
     * fragment #3 contains the remainder of the message.
     */
    @Test
    @Slow
    public void testCase6_4_2() throws Exception
    {
        byte part1[] = Hex.asByteArray("CEBAE1BDB9CF83CEBCCEB5F4"); // split code point
        byte part2[] = Hex.asByteArray("90"); // continue code point & invalid
        byte part3[] = Hex.asByteArray("8080656469746564"); // continue code point & finish

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseInfo(StatusCode.BAD_PAYLOAD).asFrame());

        Fuzzer fuzzer = new Fuzzer(this);
        try
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(new WebSocketFrame(OpCode.TEXT).setPayload(part1).setFin(false));
            TimeUnit.SECONDS.sleep(1);
            fuzzer.send(new WebSocketFrame(OpCode.CONTINUATION).setPayload(part2).setFin(false));
            TimeUnit.SECONDS.sleep(1);
            fuzzer.send(new WebSocketFrame(OpCode.CONTINUATION).setPayload(part3).setFin(true));
            fuzzer.expect(expect);
        }
        finally
        {
            fuzzer.close();
        }
    }

    /**
     * invalid text message, 1 frame/fragment (slowly, and split within code points)
     */
    @Test
    @Slow
    public void testCase6_4_3() throws Exception
    {
        // Disable Long Stacks from Parser (we know this test will throw an exception)
        try(StacklessLogging scope = new StacklessLogging(Parser.class))
        {
            ByteBuffer payload = ByteBuffer.allocate(64);
            BufferUtil.clearToFill(payload);
            payload.put(TypeUtil.fromHexString("cebae1bdb9cf83cebcceb5")); // good
            payload.put(TypeUtil.fromHexString("f4908080")); // INVALID
            payload.put(TypeUtil.fromHexString("656469746564")); // good
            BufferUtil.flipToFlush(payload,0);

            List<WebSocketFrame> send = new ArrayList<>();
            send.add(new WebSocketFrame(OpCode.TEXT).setPayload(payload));
            send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

            List<WebSocketFrame> expect = new ArrayList<>();
            expect.add(new CloseInfo(StatusCode.BAD_PAYLOAD).asFrame());

            Fuzzer fuzzer = new Fuzzer(this);
            try
            {
                fuzzer.connect();

                ByteBuffer net = fuzzer.asNetworkBuffer(send);

                int splits[] =
                { 17, 21, net.limit() };

                ByteBuffer part1 = net.slice(); // Header + good UTF
                part1.limit(splits[0]);
                ByteBuffer part2 = net.slice(); // invalid UTF
                part2.position(splits[0]);
                part2.limit(splits[1]);
                ByteBuffer part3 = net.slice(); // good UTF
                part3.position(splits[1]);
                part3.limit(splits[2]);

                fuzzer.send(part1); // the header + good utf
                TimeUnit.SECONDS.sleep(1);
                fuzzer.send(part2); // the bad UTF

                fuzzer.expect(expect);

                TimeUnit.SECONDS.sleep(1);
                fuzzer.send(part3); // the rest (shouldn't work)
                fuzzer.expectServerDisconnect(Fuzzer.DisconnectMode.UNCLEAN);
            }
            finally
            {
                fuzzer.close();
            }
        }
    }

    /**
     * invalid text message, 1 frame/fragment (slowly, and split within code points)
     */
    @Test
    @Slow
    public void testCase6_4_4() throws Exception
    {
        // Disable Long Stacks from Parser (we know this test will throw an exception)
        enableStacks(Parser.class,false);

        byte invalid[] = Hex.asByteArray("CEBAE1BDB9CF83CEBCCEB5F49080808080656469746564");

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new WebSocketFrame(OpCode.TEXT).setPayload(invalid));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseInfo(StatusCode.BAD_PAYLOAD).asFrame());

        Fuzzer fuzzer = new Fuzzer(this);
        try
        {
            fuzzer.connect();

            ByteBuffer net = fuzzer.asNetworkBuffer(send);
            fuzzer.send(net,6);
            fuzzer.send(net,11);
            TimeUnit.SECONDS.sleep(1);
            fuzzer.send(net,1);
            TimeUnit.SECONDS.sleep(1);
            fuzzer.send(net,100); // the rest

            fuzzer.expect(expect);
        }
        finally
        {
            enableStacks(Parser.class,true);
            fuzzer.close();
        }
    }
}
