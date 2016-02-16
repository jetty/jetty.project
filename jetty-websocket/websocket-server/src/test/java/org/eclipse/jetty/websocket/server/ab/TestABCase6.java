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
import org.eclipse.jetty.websocket.common.Parser;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.frames.ContinuationFrame;
import org.eclipse.jetty.websocket.common.frames.DataFrame;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.common.test.Fuzzer;
import org.eclipse.jetty.websocket.common.util.Hex;
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
     * @param frames the frames
     * @param msg the message
     */
    protected void fragmentText(List<WebSocketFrame> frames, byte msg[])
    {
        int len = msg.length;
        boolean continuation = false;
        byte mini[];
        for (int i = 0; i < len; i++)
        {
            DataFrame frame = null;
            if (continuation)
            {
                frame = new ContinuationFrame();
            }
            else
            {
                frame = new TextFrame();
            }
            mini = new byte[1];
            mini[0] = msg[i];
            frame.setPayload(ByteBuffer.wrap(mini));
            boolean isLast = (i >= (len - 1));
            frame.setFin(isLast);
            frames.add(frame);
            continuation = true;
        }
    }

    /**
     * text message, 1 frame, 0 length
     * @throws Exception on test failure
     */
    @Test
    public void testCase6_1_1() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame());
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame());
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        try (Fuzzer fuzzer = new Fuzzer(this))
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
        }
    }

    /**
     * text message, 0 length, 3 fragments
     * @throws Exception on test failure
     */
    @Test
    public void testCase6_1_2() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setFin(false));
        send.add(new ContinuationFrame().setFin(false));
        send.add(new ContinuationFrame().setFin(true));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame());
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        try (Fuzzer fuzzer = new Fuzzer(this))
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
        }
    }

    /**
     * text message, small length, 3 fragments (only middle frame has payload)
     * @throws Exception on test failure
     */
    @Test
    public void testCase6_1_3() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setFin(false));
        send.add(new ContinuationFrame().setPayload("middle").setFin(false));
        send.add(new ContinuationFrame().setFin(true));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload("middle"));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        try (Fuzzer fuzzer = new Fuzzer(this))
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
        }
    }

    /**
     * valid utf8 text message, 2 fragments (on UTF8 code point boundary)
     * @throws Exception on test failure
     */
    @Test
    public void testCase6_2_2() throws Exception
    {
        String utf1 = "Hello-\uC2B5@\uC39F\uC3A4";
        String utf2 = "\uC3BC\uC3A0\uC3A1-UTF-8!!";

        ByteBuffer b1 = ByteBuffer.wrap(StringUtil.getUtf8Bytes(utf1));
        ByteBuffer b2 = ByteBuffer.wrap(StringUtil.getUtf8Bytes(utf2));

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload(b1).setFin(false));
        send.add(new ContinuationFrame().setPayload(b2).setFin(true));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        ByteBuffer e1 = ByteBuffer.allocate(100);
        e1.put(StringUtil.getUtf8Bytes(utf1));
        e1.put(StringUtil.getUtf8Bytes(utf2));
        e1.flip();
        expect.add(new TextFrame().setPayload(e1));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        try (Fuzzer fuzzer = new Fuzzer(this))
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
        }
    }

    /**
     * valid utf8 text message, many fragments (1 byte each)
     * @throws Exception on test failure
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
        expect.add(new TextFrame().setPayload(ByteBuffer.wrap(msg)));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        try (Fuzzer fuzzer = new Fuzzer(this))
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
        }
    }

    /**
     * valid utf8 text message, many fragments (1 byte each)
     * @throws Exception on test failure
     */
    @Test
    public void testCase6_2_4() throws Exception
    {
        byte msg[] = Hex.asByteArray("CEBAE1BDB9CF83CEBCCEB5");

        List<WebSocketFrame> send = new ArrayList<>();
        fragmentText(send,msg);
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload(ByteBuffer.wrap(msg)));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        try (Fuzzer fuzzer = new Fuzzer(this))
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
        }
    }

    /**
     * invalid utf8 text message, many fragments (1 byte each)
     * @throws Exception on test failure
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

        try (Fuzzer fuzzer = new Fuzzer(this))
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
        }
    }

    /**
     * invalid text message, 3 fragments.
     * <p>
     * fragment #1 and fragment #3 are both valid in themselves.
     * <p>
     * fragment #2 contains the invalid utf8 code point.
     * @throws Exception on test failure
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

        try (Fuzzer fuzzer = new Fuzzer(this))
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);

            fuzzer.send(new TextFrame().setPayload(ByteBuffer.wrap(part1)).setFin(false));
            TimeUnit.SECONDS.sleep(1);
            fuzzer.send(new ContinuationFrame().setPayload(ByteBuffer.wrap(part2)).setFin(false));
            TimeUnit.SECONDS.sleep(1);
            fuzzer.send(new ContinuationFrame().setPayload(ByteBuffer.wrap(part3)).setFin(true));

            fuzzer.expect(expect);
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
     * @throws Exception on test failure
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

        try (Fuzzer fuzzer = new Fuzzer(this))
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(new TextFrame().setPayload(ByteBuffer.wrap(part1)).setFin(false));
            TimeUnit.SECONDS.sleep(1);
            fuzzer.send(new ContinuationFrame().setPayload(ByteBuffer.wrap(part2)).setFin(false));
            TimeUnit.SECONDS.sleep(1);
            fuzzer.send(new ContinuationFrame().setPayload(ByteBuffer.wrap(part3)).setFin(true));
            fuzzer.expect(expect);
        }
    }

    /**
     * invalid text message, 1 frame/fragment (slowly, and split within code points)
     * @throws Exception on test failure
     */
    @Test
    @Slow
    public void testCase6_4_3() throws Exception
    {
        // Disable Long Stacks from Parser (we know this test will throw an exception)
        try (StacklessLogging scope = new StacklessLogging(Parser.class))
        {
            ByteBuffer payload = ByteBuffer.allocate(64);
            BufferUtil.clearToFill(payload);
            payload.put(TypeUtil.fromHexString("cebae1bdb9cf83cebcceb5")); // good
            payload.put(TypeUtil.fromHexString("f4908080")); // INVALID
            payload.put(TypeUtil.fromHexString("656469746564")); // good
            BufferUtil.flipToFlush(payload,0);

            List<WebSocketFrame> send = new ArrayList<>();
            send.add(new TextFrame().setPayload(payload));
            send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

            List<WebSocketFrame> expect = new ArrayList<>();
            expect.add(new CloseInfo(StatusCode.BAD_PAYLOAD).asFrame());

            try (Fuzzer fuzzer = new Fuzzer(this))
            {
                fuzzer.connect();

                ByteBuffer net = fuzzer.asNetworkBuffer(send);

                int splits[] = { 17, 21, net.limit() };

                ByteBuffer part1 = net.slice(); // Header + good UTF
                part1.limit(splits[0]);
                ByteBuffer part2 = net.slice(); // invalid UTF
                part2.position(splits[0]);
                part2.limit(splits[1]);
                ByteBuffer part3 = net.slice(); // good UTF
                part3.position(splits[1]);
                part3.limit(splits[2]);

                fuzzer.send(part1); // the header + good utf
                TimeUnit.MILLISECONDS.sleep(500);
                fuzzer.send(part2); // the bad UTF
                TimeUnit.MILLISECONDS.sleep(500);
                fuzzer.send(part3); // the rest (shouldn't work)

                fuzzer.expect(expect);
            }
        }
    }

    /**
     * invalid text message, 1 frame/fragment (slowly, and split within code points)
     * @throws Exception on test failure
     */
    @Test
    @Slow
    public void testCase6_4_4() throws Exception
    {
        byte invalid[] = Hex.asByteArray("CEBAE1BDB9CF83CEBCCEB5F49080808080656469746564");

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload(ByteBuffer.wrap(invalid)));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseInfo(StatusCode.BAD_PAYLOAD).asFrame());

        try (Fuzzer fuzzer = new Fuzzer(this); StacklessLogging scope = new StacklessLogging(Parser.class))
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
    }
}
