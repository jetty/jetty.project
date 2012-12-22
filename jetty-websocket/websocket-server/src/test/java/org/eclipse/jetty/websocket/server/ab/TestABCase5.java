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

package org.eclipse.jetty.websocket.server.ab;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.toolchain.test.AdvancedRunner;
import org.eclipse.jetty.toolchain.test.annotation.Slow;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.Parser;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Fragmentation Tests
 */
@RunWith(AdvancedRunner.class)
public class TestABCase5 extends AbstractABCase
{
    /**
     * Send ping fragmented in 2 packets
     */
    @Test
    public void testCase5_1() throws Exception
    {
        // Disable Long Stacks from Parser (we know this test will throw an exception)
        enableStacks(Parser.class,false);

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new WebSocketFrame(OpCode.PING).setPayload("hello, ").setFin(false));
        send.add(new WebSocketFrame(OpCode.CONTINUATION).setPayload("world"));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseInfo(StatusCode.PROTOCOL).asFrame());

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
            enableStacks(Parser.class,true);
        }
    }

    /**
     * Send continuation+fin, then text+fin (framewise)
     */
    @Test
    public void testCase5_10() throws Exception
    {
        // Disable Long Stacks from Parser (we know this test will throw an exception)
        enableStacks(Parser.class,false);

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new WebSocketFrame(OpCode.CONTINUATION).setPayload("sorry").setFin(true));
        send.add(new WebSocketFrame(OpCode.TEXT).setPayload("hello, world"));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseInfo(StatusCode.PROTOCOL).asFrame());

        Fuzzer fuzzer = new Fuzzer(this);
        try
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.PER_FRAME);
            fuzzer.sendAndIgnoreBrokenPipe(send);
            fuzzer.expect(expect);
        }
        finally
        {
            enableStacks(Parser.class,true);
            fuzzer.close();
        }
    }

    /**
     * Send continuation+fin, then text+fin (slowly)
     */
    @Test
    public void testCase5_11() throws Exception
    {
        // Disable Long Stacks from Parser (we know this test will throw an exception)
        enableStacks(Parser.class,false);

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new WebSocketFrame(OpCode.CONTINUATION).setPayload("sorry").setFin(true));
        send.add(new WebSocketFrame(OpCode.TEXT).setPayload("hello, world"));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseInfo(StatusCode.PROTOCOL).asFrame());

        Fuzzer fuzzer = new Fuzzer(this);
        try
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.SLOW);
            fuzzer.setSlowSendSegmentSize(1);
            fuzzer.sendAndIgnoreBrokenPipe(send);
            fuzzer.expect(expect);
        }
        finally
        {
            enableStacks(Parser.class,true);
            fuzzer.close();
        }
    }

    /**
     * Send continuation+!fin, then text+fin
     */
    @Test
    public void testCase5_12() throws Exception
    {
        // Disable Long Stacks from Parser (we know this test will throw an exception)
        enableStacks(Parser.class,false);

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new WebSocketFrame(OpCode.CONTINUATION).setPayload("sorry").setFin(false));
        send.add(new WebSocketFrame(OpCode.TEXT).setPayload("hello, world"));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseInfo(StatusCode.PROTOCOL).asFrame());

        Fuzzer fuzzer = new Fuzzer(this);
        try
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.sendAndIgnoreBrokenPipe(send);
            fuzzer.expect(expect);
        }
        finally
        {
            enableStacks(Parser.class,true);
            fuzzer.close();
        }
    }

    /**
     * Send continuation+!fin, then text+fin (framewise)
     */
    @Test
    public void testCase5_13() throws Exception
    {
        // Disable Long Stacks from Parser (we know this test will throw an exception)
        enableStacks(Parser.class,false);
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new WebSocketFrame(OpCode.CONTINUATION).setPayload("sorry").setFin(false));
        send.add(new WebSocketFrame(OpCode.TEXT).setPayload("hello, world"));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseInfo(StatusCode.PROTOCOL).asFrame());

        Fuzzer fuzzer = new Fuzzer(this);
        try
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.PER_FRAME);
            fuzzer.sendAndIgnoreBrokenPipe(send);
            fuzzer.expect(expect);
        }
        finally
        {
            enableStacks(Parser.class,true);
            fuzzer.close();
        }
    }

    /**
     * Send continuation+!fin, then text+fin (slowly)
     */
    @Test
    public void testCase5_14() throws Exception
    {
        // Disable Long Stacks from Parser (we know this test will throw an exception)
        enableStacks(Parser.class,false);

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new WebSocketFrame(OpCode.CONTINUATION).setPayload("sorry").setFin(false));
        send.add(new WebSocketFrame(OpCode.TEXT).setPayload("hello, world"));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseInfo(StatusCode.PROTOCOL).asFrame());

        Fuzzer fuzzer = new Fuzzer(this);
        try
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.SLOW);
            fuzzer.setSlowSendSegmentSize(1);
            fuzzer.sendAndIgnoreBrokenPipe(send);
            fuzzer.expect(expect);
        }
        finally
        {
            enableStacks(Parser.class,false);
            fuzzer.close();
        }
    }

    /**
     * Send text fragmented properly in 2 frames, then continuation!fin, then text unfragmented.
     */
    @Test
    public void testCase5_15() throws Exception
    {
        // Disable Long Stacks from Parser (we know this test will throw an exception)
        enableStacks(Parser.class,false);

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new WebSocketFrame(OpCode.TEXT).setPayload("fragment1").setFin(false));
        send.add(new WebSocketFrame(OpCode.CONTINUATION).setPayload("fragment2").setFin(true));
        send.add(new WebSocketFrame(OpCode.CONTINUATION).setPayload("fragment3").setFin(false)); // bad frame
        send.add(new WebSocketFrame(OpCode.TEXT).setPayload("fragment4").setFin(true));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(WebSocketFrame.text("fragment1fragment2"));
        expect.add(new CloseInfo(StatusCode.PROTOCOL).asFrame());

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
            enableStacks(Parser.class,true);
            fuzzer.close();
        }
    }

    /**
     * (continuation!fin, text!fin, continuation+fin) * 2
     */
    @Test
    public void testCase5_16() throws Exception
    {
        // Disable Long Stacks from Parser (we know this test will throw an exception)
        enableStacks(Parser.class,false);

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new WebSocketFrame(OpCode.CONTINUATION).setPayload("fragment1").setFin(false)); // bad frame
        send.add(new WebSocketFrame(OpCode.TEXT).setPayload("fragment2").setFin(false));
        send.add(new WebSocketFrame(OpCode.CONTINUATION).setPayload("fragment3").setFin(true));
        send.add(new WebSocketFrame(OpCode.CONTINUATION).setPayload("fragment4").setFin(false)); // bad frame
        send.add(new WebSocketFrame(OpCode.TEXT).setPayload("fragment5").setFin(false));
        send.add(new WebSocketFrame(OpCode.CONTINUATION).setPayload("fragment6").setFin(true));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseInfo(StatusCode.PROTOCOL).asFrame());

        Fuzzer fuzzer = new Fuzzer(this);
        try
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.sendAndIgnoreBrokenPipe(send);
            fuzzer.expect(expect);
        }
        finally
        {
            enableStacks(Parser.class,true);
            fuzzer.close();
        }
    }

    /**
     * (continuation+fin, text!fin, continuation+fin) * 2
     */
    @Test
    public void testCase5_17() throws Exception
    {
        // Disable Long Stacks from Parser (we know this test will throw an exception)
        enableStacks(Parser.class,false);

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new WebSocketFrame(OpCode.CONTINUATION).setPayload("fragment1").setFin(true)); // nothing to continue
        send.add(new WebSocketFrame(OpCode.TEXT).setPayload("fragment2").setFin(false));
        send.add(new WebSocketFrame(OpCode.CONTINUATION).setPayload("fragment3").setFin(true));
        send.add(new WebSocketFrame(OpCode.CONTINUATION).setPayload("fragment4").setFin(true)); // nothing to continue
        send.add(new WebSocketFrame(OpCode.TEXT).setPayload("fragment5").setFin(false));
        send.add(new WebSocketFrame(OpCode.CONTINUATION).setPayload("fragment6").setFin(true));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseInfo(StatusCode.PROTOCOL).asFrame());

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
            enableStacks(Parser.class,true);
            fuzzer.close();
        }
    }

    /**
     * text message fragmented in 2 frames, both frames as opcode=TEXT
     */
    @Test
    public void testCase5_18() throws Exception
    {
        // Disable Long Stacks from Parser (we know this test will throw an exception)
        enableStacks(Parser.class,false);

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new WebSocketFrame(OpCode.TEXT).setPayload("fragment1").setFin(false));
        send.add(new WebSocketFrame(OpCode.TEXT).setPayload("fragment2").setFin(true)); // bad frame, must be continuation
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseInfo(StatusCode.PROTOCOL).asFrame());

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
            enableStacks(Parser.class,true);
            fuzzer.close();
        }
    }

    /**
     * send text message fragmented in 5 frames, with 2 pings and wait between.
     */
    @Test
    @Slow
    public void testCase5_19() throws Exception
    {
        // phase 1
        List<WebSocketFrame> send1 = new ArrayList<>();
        send1.add(new WebSocketFrame(OpCode.TEXT).setPayload("f1").setFin(false));
        send1.add(new WebSocketFrame(OpCode.CONTINUATION).setPayload(",f2").setFin(false));
        send1.add(new WebSocketFrame(OpCode.PING).setPayload("pong-1"));

        List<WebSocketFrame> expect1 = new ArrayList<>();
        expect1.add(WebSocketFrame.pong().setPayload("pong-1"));

        // phase 2
        List<WebSocketFrame> send2 = new ArrayList<>();
        send2.add(new WebSocketFrame(OpCode.CONTINUATION).setPayload(",f3").setFin(false));
        send2.add(new WebSocketFrame(OpCode.CONTINUATION).setPayload(",f4").setFin(false));
        send2.add(new WebSocketFrame(OpCode.PING).setPayload("pong-2"));
        send2.add(new WebSocketFrame(OpCode.CONTINUATION).setPayload(",f5").setFin(true));
        send2.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect2 = new ArrayList<>();
        expect2.add(WebSocketFrame.pong().setPayload("pong-2"));
        expect2.add(WebSocketFrame.text("f1,f2,f3,f4,f5"));
        expect2.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        Fuzzer fuzzer = new Fuzzer(this);
        try
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);

            // phase 1
            fuzzer.send(send1);
            fuzzer.expect(expect1);

            // delay
            TimeUnit.SECONDS.sleep(1);

            // phase 2
            fuzzer.send(send2);
            fuzzer.expect(expect2);
        }
        finally
        {
            fuzzer.close();
        }
    }

    /**
     * Send pong fragmented in 2 packets
     */
    @Test
    public void testCase5_2() throws Exception
    {
        // Disable Long Stacks from Parser (we know this test will throw an exception)
        enableStacks(Parser.class,false);

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new WebSocketFrame(OpCode.PONG).setPayload("hello, ").setFin(false));
        send.add(new WebSocketFrame(OpCode.CONTINUATION).setPayload("world"));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseInfo(StatusCode.PROTOCOL).asFrame());

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
            enableStacks(Parser.class,true);
            fuzzer.close();
        }
    }

    /**
     * send text message fragmented in 5 frames, with 2 pings and wait between. (framewise)
     */
    @Test
    public void testCase5_20() throws Exception
    {
        List<WebSocketFrame> send1 = new ArrayList<>();
        send1.add(new WebSocketFrame(OpCode.TEXT).setPayload("f1").setFin(false));
        send1.add(new WebSocketFrame(OpCode.CONTINUATION).setPayload(",f2").setFin(false));
        send1.add(new WebSocketFrame(OpCode.PING).setPayload("pong-1"));

        List<WebSocketFrame> send2 = new ArrayList<>();
        send2.add(new WebSocketFrame(OpCode.CONTINUATION).setPayload(",f3").setFin(false));
        send2.add(new WebSocketFrame(OpCode.CONTINUATION).setPayload(",f4").setFin(false));
        send2.add(new WebSocketFrame(OpCode.PING).setPayload("pong-2"));
        send2.add(new WebSocketFrame(OpCode.CONTINUATION).setPayload(",f5").setFin(true));
        send2.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect1 = new ArrayList<>();
        expect1.add(WebSocketFrame.pong().setPayload("pong-1"));

        List<WebSocketFrame> expect2 = new ArrayList<>();
        expect2.add(WebSocketFrame.pong().setPayload("pong-2"));
        expect2.add(WebSocketFrame.text("f1,f2,f3,f4,f5"));
        expect2.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        Fuzzer fuzzer = new Fuzzer(this);
        try
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.PER_FRAME);

            fuzzer.send(send1);
            fuzzer.expect(expect1);

            TimeUnit.SECONDS.sleep(1);

            fuzzer.send(send2);
            fuzzer.expect(expect2);
        }
        finally
        {
            fuzzer.close();
        }
    }

    /**
     * send text message fragmented in 5 frames, with 2 pings and wait between. (framewise)
     */
    @Test
    public void testCase5_20_slow() throws Exception
    {
        List<WebSocketFrame> send1 = new ArrayList<>();
        send1.add(new WebSocketFrame(OpCode.TEXT).setPayload("f1").setFin(false));
        send1.add(new WebSocketFrame(OpCode.CONTINUATION).setPayload(",f2").setFin(false));
        send1.add(new WebSocketFrame(OpCode.PING).setPayload("pong-1"));

        List<WebSocketFrame> send2 = new ArrayList<>();
        send2.add(new WebSocketFrame(OpCode.CONTINUATION).setPayload(",f3").setFin(false));
        send2.add(new WebSocketFrame(OpCode.CONTINUATION).setPayload(",f4").setFin(false));
        send2.add(new WebSocketFrame(OpCode.PING).setPayload("pong-2"));
        send2.add(new WebSocketFrame(OpCode.CONTINUATION).setPayload(",f5").setFin(true));
        send2.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect1 = new ArrayList<>();
        expect1.add(WebSocketFrame.pong().setPayload("pong-1"));

        List<WebSocketFrame> expect2 = new ArrayList<>();
        expect2.add(WebSocketFrame.pong().setPayload("pong-2"));
        expect2.add(WebSocketFrame.text("f1,f2,f3,f4,f5"));
        expect2.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        Fuzzer fuzzer = new Fuzzer(this);
        try
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.SLOW);
            fuzzer.setSlowSendSegmentSize(1);

            fuzzer.send(send1);
            fuzzer.expect(expect1);

            TimeUnit.SECONDS.sleep(1);

            fuzzer.send(send2);
            fuzzer.expect(expect2);
        }
        finally
        {
            fuzzer.close();
        }
    }

    /**
     * Send text fragmented in 2 packets
     */
    @Test
    public void testCase5_3() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new WebSocketFrame(OpCode.TEXT).setPayload("hello, ").setFin(false));
        send.add(new WebSocketFrame(OpCode.CONTINUATION).setPayload("world").setFin(true));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(WebSocketFrame.text("hello, world"));
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
     * Send text fragmented in 2 packets (framewise)
     */
    @Test
    public void testCase5_4() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new WebSocketFrame(OpCode.TEXT).setPayload("hello, ").setFin(false));
        send.add(new WebSocketFrame(OpCode.CONTINUATION).setPayload("world").setFin(true));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(WebSocketFrame.text("hello, world"));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        Fuzzer fuzzer = new Fuzzer(this);
        try
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.PER_FRAME);
            fuzzer.send(send);
            fuzzer.expect(expect);
        }
        finally
        {
            fuzzer.close();
        }
    }

    /**
     * Send text fragmented in 2 packets (slowly)
     */
    @Test
    public void testCase5_5() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new WebSocketFrame(OpCode.TEXT).setPayload("hello, ").setFin(false));
        send.add(new WebSocketFrame(OpCode.CONTINUATION).setPayload("world").setFin(true));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(WebSocketFrame.text("hello, world"));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        Fuzzer fuzzer = new Fuzzer(this);
        try
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.SLOW);
            fuzzer.setSlowSendSegmentSize(1);
            fuzzer.send(send);
            fuzzer.expect(expect);
        }
        finally
        {
            fuzzer.close();
        }
    }

    /**
     * Send text fragmented in 2 packets, with ping between them
     */
    @Test
    public void testCase5_6() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new WebSocketFrame(OpCode.TEXT).setPayload("hello, ").setFin(false));
        send.add(new WebSocketFrame(OpCode.PING).setPayload("ping"));
        send.add(new WebSocketFrame(OpCode.CONTINUATION).setPayload("world").setFin(true));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(WebSocketFrame.pong().setPayload("ping"));
        expect.add(WebSocketFrame.text("hello, world"));
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
     * Send text fragmented in 2 packets, with ping between them (framewise)
     */
    @Test
    public void testCase5_7() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new WebSocketFrame(OpCode.TEXT).setPayload("hello, ").setFin(false));
        send.add(new WebSocketFrame(OpCode.PING).setPayload("ping"));
        send.add(new WebSocketFrame(OpCode.CONTINUATION).setPayload("world").setFin(true));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(WebSocketFrame.pong().setPayload("ping"));
        expect.add(WebSocketFrame.text("hello, world"));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        Fuzzer fuzzer = new Fuzzer(this);
        try
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.PER_FRAME);
            fuzzer.send(send);
            fuzzer.expect(expect);
        }
        finally
        {
            fuzzer.close();
        }
    }

    /**
     * Send text fragmented in 2 packets, with ping between them (slowly)
     */
    @Test
    public void testCase5_8() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new WebSocketFrame(OpCode.TEXT).setPayload("hello, ").setFin(false));
        send.add(new WebSocketFrame(OpCode.PING).setPayload("ping"));
        send.add(new WebSocketFrame(OpCode.CONTINUATION).setPayload("world").setFin(true));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(WebSocketFrame.pong().setPayload("ping"));
        expect.add(WebSocketFrame.text("hello, world"));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        Fuzzer fuzzer = new Fuzzer(this);
        try
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.SLOW);
            fuzzer.setSlowSendSegmentSize(1);
            fuzzer.send(send);
            fuzzer.expect(expect);
        }
        finally
        {
            fuzzer.close();
        }
    }

    /**
     * Send continuation+fin, then text+fin
     */
    @Test
    public void testCase5_9() throws Exception
    {
        // Disable Long Stacks from Parser (we know this test will throw an exception)
        enableStacks(Parser.class,false);

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new WebSocketFrame(OpCode.CONTINUATION).setPayload("sorry").setFin(true));
        send.add(new WebSocketFrame(OpCode.TEXT).setPayload("hello, world"));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseInfo(StatusCode.PROTOCOL).asFrame());

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
            enableStacks(Parser.class,true);
            fuzzer.close();
        }
    }
}
