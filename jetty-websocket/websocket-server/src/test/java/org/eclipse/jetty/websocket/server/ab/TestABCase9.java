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
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.toolchain.test.AdvancedRunner;
import org.eclipse.jetty.toolchain.test.annotation.Stress;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.frames.BinaryFrame;
import org.eclipse.jetty.websocket.common.frames.ContinuationFrame;
import org.eclipse.jetty.websocket.common.frames.DataFrame;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.common.test.Fuzzer;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Big frame/message tests
 */
@RunWith(AdvancedRunner.class)
public class TestABCase9 extends AbstractABCase
{
    private static final int KBYTE = 1024;
    private static final int MBYTE = KBYTE * KBYTE;

    private DataFrame toDataFrame(byte op)
    {
        switch (op)
        {
            case OpCode.BINARY:
                return new BinaryFrame();
            case OpCode.TEXT:
                return new TextFrame();
            case OpCode.CONTINUATION:
                return new ContinuationFrame();
            default:
                throw new IllegalArgumentException("Not a data frame: " + op);
        }
    }

    private void assertMultiFrameEcho(byte opcode, int overallMsgSize, int fragmentSize) throws Exception
    {
        byte msg[] = new byte[overallMsgSize];
        Arrays.fill(msg,(byte)'M');

        List<WebSocketFrame> send = new ArrayList<>();
        byte frag[];
        int remaining = msg.length;
        int offset = 0;
        boolean fin;
        ByteBuffer buf;
        ;
        byte op = opcode;
        while (remaining > 0)
        {
            int len = Math.min(remaining,fragmentSize);
            frag = new byte[len];
            System.arraycopy(msg,offset,frag,0,len);
            remaining -= len;
            fin = (remaining <= 0);
            buf = ByteBuffer.wrap(frag);

            send.add(toDataFrame(op).setPayload(buf).setFin(fin));

            offset += len;
            op = OpCode.CONTINUATION;
        }
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(toDataFrame(opcode).setPayload(copyOf(msg)));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        try(Fuzzer fuzzer = new Fuzzer(this))
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect,8,TimeUnit.SECONDS);
        }
    }

    private void assertSlowFrameEcho(byte opcode, int overallMsgSize, int segmentSize) throws Exception
    {
        byte msg[] = new byte[overallMsgSize];
        Arrays.fill(msg,(byte)'M');
        ByteBuffer buf = ByteBuffer.wrap(msg);

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(toDataFrame(opcode).setPayload(buf));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(toDataFrame(opcode).setPayload(clone(buf)));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        try(Fuzzer fuzzer = new Fuzzer(this))
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.SLOW);
            fuzzer.setSlowSendSegmentSize(segmentSize);
            fuzzer.send(send);
            fuzzer.expect(expect,8,TimeUnit.SECONDS);
        }
    }

    /**
     * Echo 64KB text message (1 frame)
     * @throws Exception on test failure
     */
    @Test
    public void testCase9_1_1() throws Exception
    {
        byte utf[] = new byte[64 * KBYTE];
        Arrays.fill(utf,(byte)'y');
        String msg = StringUtil.toUTF8String(utf,0,utf.length);

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload(msg));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload(msg));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        try(Fuzzer fuzzer = new Fuzzer(this))
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
        }
    }

    /**
     * Echo 256KB text message (1 frame)
     * @throws Exception on test failure
     */
    @Test
    public void testCase9_1_2() throws Exception
    {
        byte utf[] = new byte[256 * KBYTE];
        Arrays.fill(utf,(byte)'y');
        ByteBuffer buf = ByteBuffer.wrap(utf);

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload(buf));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload(clone(buf)));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        try(Fuzzer fuzzer = new Fuzzer(this))
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
        }
    }

    /**
     * Echo 1MB text message (1 frame)
     * @throws Exception on test failure
     */
    @Test
    public void testCase9_1_3() throws Exception
    {
        byte utf[] = new byte[1 * MBYTE];
        Arrays.fill(utf,(byte)'y');
        ByteBuffer buf = ByteBuffer.wrap(utf);

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload(buf));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload(clone(buf)));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        try(Fuzzer fuzzer = new Fuzzer(this))
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect,4,TimeUnit.SECONDS);
        }
    }

    /**
     * Echo 4MB text message (1 frame)
     * @throws Exception on test failure
     */
    @Test
    public void testCase9_1_4() throws Exception
    {
        byte utf[] = new byte[4 * MBYTE];
        Arrays.fill(utf,(byte)'y');
        ByteBuffer buf = ByteBuffer.wrap(utf);

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload(buf));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload(clone(buf)));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        try(Fuzzer fuzzer = new Fuzzer(this))
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect,8,TimeUnit.SECONDS);
        }
    }

    /**
     * Echo 8MB text message (1 frame)
     * @throws Exception on test failure
     */
    @Test
    @Stress("High I/O use")
    public void testCase9_1_5() throws Exception
    {
        byte utf[] = new byte[8 * MBYTE];
        Arrays.fill(utf,(byte)'y');
        ByteBuffer buf = ByteBuffer.wrap(utf);

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload(buf));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload(clone(buf)));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        try(Fuzzer fuzzer = new Fuzzer(this))
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect,16,TimeUnit.SECONDS);
        }
    }

    /**
     * Echo 16MB text message (1 frame)
     * @throws Exception on test failure
     */
    @Test
    @Stress("High I/O use")
    public void testCase9_1_6() throws Exception
    {
        byte utf[] = new byte[16 * MBYTE];
        Arrays.fill(utf,(byte)'y');
        ByteBuffer buf = ByteBuffer.wrap(utf);

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload(buf));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload(clone(buf)));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        try(Fuzzer fuzzer = new Fuzzer(this))
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect,32,TimeUnit.SECONDS);
        }
    }

    /**
     * Echo 64KB binary message (1 frame)
     * @throws Exception on test failure
     */
    @Test
    public void testCase9_2_1() throws Exception
    {
        byte data[] = new byte[64 * KBYTE];
        Arrays.fill(data,(byte)0x21);

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new BinaryFrame().setPayload(data));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new BinaryFrame().setPayload(copyOf(data)));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        try(Fuzzer fuzzer = new Fuzzer(this))
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
        }
    }

    /**
     * Echo 256KB binary message (1 frame)
     * @throws Exception on test failure
     */
    @Test
    public void testCase9_2_2() throws Exception
    {
        byte data[] = new byte[256 * KBYTE];
        Arrays.fill(data,(byte)0x22);
        ByteBuffer buf = ByteBuffer.wrap(data);

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new BinaryFrame().setPayload(buf));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new BinaryFrame().setPayload(clone(buf)));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        try(Fuzzer fuzzer = new Fuzzer(this))
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
        }
    }

    /**
     * Echo 1MB binary message (1 frame)
     * @throws Exception on test failure
     */
    @Test
    @Stress("High I/O use")
    public void testCase9_2_3() throws Exception
    {
        byte data[] = new byte[1 * MBYTE];
        Arrays.fill(data,(byte)0x23);
        ByteBuffer buf = ByteBuffer.wrap(data);

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new BinaryFrame().setPayload(buf));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new BinaryFrame().setPayload(clone(buf)));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        try(Fuzzer fuzzer = new Fuzzer(this))
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect,4,TimeUnit.SECONDS);
        }
    }

    /**
     * Echo 4MB binary message (1 frame)
     * @throws Exception on test failure
     */
    @Test
    @Stress("High I/O use")
    public void testCase9_2_4() throws Exception
    {
        byte data[] = new byte[4 * MBYTE];
        Arrays.fill(data,(byte)0x24);
        ByteBuffer buf = ByteBuffer.wrap(data);

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new BinaryFrame().setPayload(buf));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new BinaryFrame().setPayload(clone(buf)));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        try(Fuzzer fuzzer = new Fuzzer(this))
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect,8,TimeUnit.SECONDS);
        }
    }

    /**
     * Echo 8MB binary message (1 frame)
     * @throws Exception on test failure
     */
    @Test
    @Stress("High I/O use")
    public void testCase9_2_5() throws Exception
    {
        byte data[] = new byte[8 * MBYTE];
        Arrays.fill(data,(byte)0x25);
        ByteBuffer buf = ByteBuffer.wrap(data);

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new BinaryFrame().setPayload(buf));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new BinaryFrame().setPayload(clone(buf)));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        try(Fuzzer fuzzer = new Fuzzer(this))
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect,16,TimeUnit.SECONDS);
        }
    }

    /**
     * Echo 16MB binary message (1 frame)
     * @throws Exception on test failure
     */
    @Test
    @Stress("High I/O use")
    public void testCase9_2_6() throws Exception
    {
        byte data[] = new byte[16 * MBYTE];
        Arrays.fill(data,(byte)0x26);
        ByteBuffer buf = ByteBuffer.wrap(data);

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new BinaryFrame().setPayload(buf));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new BinaryFrame().setPayload(clone(buf)));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        try(Fuzzer fuzzer = new Fuzzer(this))
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect,32,TimeUnit.SECONDS);
        }
    }

    /**
     * Send 4MB text message in multiple frames.
     * @throws Exception on test failure
     */
    @Test
    @Stress("High I/O use")
    public void testCase9_3_1() throws Exception
    {
        assertMultiFrameEcho(OpCode.TEXT,4 * MBYTE,64);
    }

    /**
     * Send 4MB text message in multiple frames.
     * @throws Exception on test failure
     */
    @Test
    @Stress("High I/O use")
    public void testCase9_3_2() throws Exception
    {
        assertMultiFrameEcho(OpCode.TEXT,4 * MBYTE,256);
    }

    /**
     * Send 4MB text message in multiple frames.
     * @throws Exception on test failure
     */
    @Test
    @Stress("High I/O use")
    public void testCase9_3_3() throws Exception
    {
        assertMultiFrameEcho(OpCode.TEXT,4 * MBYTE,1 * KBYTE);
    }

    /**
     * Send 4MB text message in multiple frames.
     * @throws Exception on test failure
     */
    @Test
    @Stress("High I/O use")
    public void testCase9_3_4() throws Exception
    {
        assertMultiFrameEcho(OpCode.TEXT,4 * MBYTE,4 * KBYTE);
    }

    /**
     * Send 4MB text message in multiple frames.
     * @throws Exception on test failure
     */
    @Test
    @Stress("High I/O use")
    public void testCase9_3_5() throws Exception
    {
        assertMultiFrameEcho(OpCode.TEXT,4 * MBYTE,16 * KBYTE);
    }

    /**
     * Send 4MB text message in multiple frames.
     * @throws Exception on test failure
     */
    @Test
    @Stress("High I/O use")
    public void testCase9_3_6() throws Exception
    {
        assertMultiFrameEcho(OpCode.TEXT,4 * MBYTE,64 * KBYTE);
    }

    /**
     * Send 4MB text message in multiple frames.
     * @throws Exception on test failure
     */
    @Test
    @Stress("High I/O use")
    public void testCase9_3_7() throws Exception
    {
        assertMultiFrameEcho(OpCode.TEXT,4 * MBYTE,256 * KBYTE);
    }

    /**
     * Send 4MB text message in multiple frames.
     * @throws Exception on test failure
     */
    @Test
    @Stress("High I/O use")
    public void testCase9_3_8() throws Exception
    {
        assertMultiFrameEcho(OpCode.TEXT,4 * MBYTE,1 * MBYTE);
    }

    /**
     * Send 4MB text message in multiple frames.
     * @throws Exception on test failure
     */
    @Test
    @Stress("High I/O use")
    public void testCase9_3_9() throws Exception
    {
        assertMultiFrameEcho(OpCode.TEXT,4 * MBYTE,4 * MBYTE);
    }

    /**
     * Send 4MB binary message in multiple frames.
     * @throws Exception on test failure
     */
    @Test
    @Stress("High I/O use")
    public void testCase9_4_1() throws Exception
    {
        assertMultiFrameEcho(OpCode.BINARY,4 * MBYTE,64);
    }

    /**
     * Send 4MB binary message in multiple frames.
     * @throws Exception on test failure
     */
    @Test
    @Stress("High I/O use")
    public void testCase9_4_2() throws Exception
    {
        assertMultiFrameEcho(OpCode.BINARY,4 * MBYTE,256);
    }

    /**
     * Send 4MB binary message in multiple frames.
     * @throws Exception on test failure
     */
    @Test
    @Stress("High I/O use")
    public void testCase9_4_3() throws Exception
    {
        assertMultiFrameEcho(OpCode.BINARY,4 * MBYTE,1 * KBYTE);
    }

    /**
     * Send 4MB binary message in multiple frames.
     * @throws Exception on test failure
     */
    @Test
    @Stress("High I/O use")
    public void testCase9_4_4() throws Exception
    {
        assertMultiFrameEcho(OpCode.BINARY,4 * MBYTE,4 * KBYTE);
    }

    /**
     * Send 4MB binary message in multiple frames.
     * @throws Exception on test failure
     */
    @Test
    @Stress("High I/O use")
    public void testCase9_4_5() throws Exception
    {
        assertMultiFrameEcho(OpCode.BINARY,4 * MBYTE,16 * KBYTE);
    }

    /**
     * Send 4MB binary message in multiple frames.
     * @throws Exception on test failure
     */
    @Test
    @Stress("High I/O use")
    public void testCase9_4_6() throws Exception
    {
        assertMultiFrameEcho(OpCode.BINARY,4 * MBYTE,64 * KBYTE);
    }

    /**
     * Send 4MB binary message in multiple frames.
     * @throws Exception on test failure
     */
    @Test
    @Stress("High I/O use")
    public void testCase9_4_7() throws Exception
    {
        assertMultiFrameEcho(OpCode.BINARY,4 * MBYTE,256 * KBYTE);
    }

    /**
     * Send 4MB binary message in multiple frames.
     * @throws Exception on test failure
     */
    @Test
    @Stress("High I/O use")
    public void testCase9_4_8() throws Exception
    {
        assertMultiFrameEcho(OpCode.BINARY,4 * MBYTE,1 * MBYTE);
    }

    /**
     * Send 4MB binary message in multiple frames.
     * @throws Exception on test failure
     */
    @Test
    @Stress("High I/O use")
    public void testCase9_4_9() throws Exception
    {
        assertMultiFrameEcho(OpCode.BINARY,4 * MBYTE,4 * MBYTE);
    }

    /**
     * Send 1MB text message in 1 frame, but slowly
     * @throws Exception on test failure
     */
    @Test
    @Stress("High I/O use")
    public void testCase9_5_1() throws Exception
    {
        assertSlowFrameEcho(OpCode.TEXT,1 * MBYTE,64);
    }

    /**
     * Send 1MB text message in 1 frame, but slowly
     * @throws Exception on test failure
     */
    @Test
    @Stress("High I/O use")
    public void testCase9_5_2() throws Exception
    {
        assertSlowFrameEcho(OpCode.TEXT,1 * MBYTE,128);
    }

    /**
     * Send 1MB text message in 1 frame, but slowly
     * @throws Exception on test failure
     */
    @Test
    @Stress("High I/O use")
    public void testCase9_5_3() throws Exception
    {
        assertSlowFrameEcho(OpCode.TEXT,1 * MBYTE,256);
    }

    /**
     * Send 1MB text message in 1 frame, but slowly
     * @throws Exception on test failure
     */
    @Test
    @Stress("High I/O use")
    public void testCase9_5_4() throws Exception
    {
        assertSlowFrameEcho(OpCode.TEXT,1 * MBYTE,512);
    }

    /**
     * Send 1MB text message in 1 frame, but slowly
     * @throws Exception on test failure
     */
    @Test
    @Stress("High I/O use")
    public void testCase9_5_5() throws Exception
    {
        assertSlowFrameEcho(OpCode.TEXT,1 * MBYTE,1024);
    }

    /**
     * Send 1MB text message in 1 frame, but slowly
     * @throws Exception on test failure
     */
    @Test
    @Stress("High I/O use")
    public void testCase9_5_6() throws Exception
    {
        assertSlowFrameEcho(OpCode.TEXT,1 * MBYTE,2048);
    }

    /**
     * Send 1MB binary message in 1 frame, but slowly
     * @throws Exception on test failure
     */
    @Test
    @Stress("High I/O use")
    public void testCase9_6_1() throws Exception
    {
        assertSlowFrameEcho(OpCode.BINARY,1 * MBYTE,64);
    }

    /**
     * Send 1MB binary message in 1 frame, but slowly
     * @throws Exception on test failure
     */
    @Test
    @Stress("High I/O use")
    public void testCase9_6_2() throws Exception
    {
        assertSlowFrameEcho(OpCode.BINARY,1 * MBYTE,128);
    }

    /**
     * Send 1MB binary message in 1 frame, but slowly
     * @throws Exception on test failure
     */
    @Test
    @Stress("High I/O use")
    public void testCase9_6_3() throws Exception
    {
        assertSlowFrameEcho(OpCode.BINARY,1 * MBYTE,256);
    }

    /**
     * Send 1MB binary message in 1 frame, but slowly
     * @throws Exception on test failure
     */
    @Test
    @Stress("High I/O use")
    public void testCase9_6_4() throws Exception
    {
        assertSlowFrameEcho(OpCode.BINARY,1 * MBYTE,512);
    }

    /**
     * Send 1MB binary message in 1 frame, but slowly
     * @throws Exception on test failure
     */
    @Test
    @Stress("High I/O use")
    public void testCase9_6_5() throws Exception
    {
        assertSlowFrameEcho(OpCode.BINARY,1 * MBYTE,1024);
    }

    /**
     * Send 1MB binary message in 1 frame, but slowly
     * @throws Exception on test failure
     */
    @Test
    @Stress("High I/O use")
    public void testCase9_6_6() throws Exception
    {
        assertSlowFrameEcho(OpCode.BINARY,1 * MBYTE,2048);
    }
}
