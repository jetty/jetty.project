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

import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.frames.BinaryFrame;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.common.test.Fuzzer;
import org.eclipse.jetty.websocket.common.test.Fuzzer.SendMode;
import org.junit.Test;

public class TestABCase1 extends AbstractABCase
{
    /**
     * Echo 0 byte TEXT message
     * @throws Exception on test failure
     */
    @Test
    public void testCase1_1_1() throws Exception
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
            fuzzer.setSendMode(SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
        }
    }

    /**
     * Echo 125 byte TEXT message (uses small 7-bit payload length)
     * @throws Exception on test failure
     */
    @Test
    public void testCase1_1_2() throws Exception
    {
        byte payload[] = new byte[125];
        Arrays.fill(payload,(byte)'*');
        ByteBuffer buf = ByteBuffer.wrap(payload);

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload(buf));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload(clone(buf)));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        try (Fuzzer fuzzer = new Fuzzer(this))
        {
            fuzzer.connect();
            fuzzer.setSendMode(SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
        }
    }

    /**
     * Echo 126 byte TEXT message (uses medium 2 byte payload length)
     * @throws Exception on test failure
     */
    @Test
    public void testCase1_1_3() throws Exception
    {
        byte payload[] = new byte[126];
        Arrays.fill(payload,(byte)'*');
        ByteBuffer buf = ByteBuffer.wrap(payload);

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload(buf));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload(clone(buf)));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        try (Fuzzer fuzzer = new Fuzzer(this))
        {
            fuzzer.connect();
            fuzzer.setSendMode(SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
        }
    }

    /**
     * Echo 127 byte TEXT message (uses medium 2 byte payload length)
     * @throws Exception on test failure
     */
    @Test
    public void testCase1_1_4() throws Exception
    {
        byte payload[] = new byte[127];
        Arrays.fill(payload,(byte)'*');
        ByteBuffer buf = ByteBuffer.wrap(payload);

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload(buf));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload(clone(buf)));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        try (Fuzzer fuzzer = new Fuzzer(this))
        {
            fuzzer.connect();
            fuzzer.setSendMode(SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
        }
    }

    /**
     * Echo 128 byte TEXT message (uses medium 2 byte payload length)
     * @throws Exception on test failure
     */
    @Test
    public void testCase1_1_5() throws Exception
    {
        byte payload[] = new byte[128];
        Arrays.fill(payload,(byte)'*');
        ByteBuffer buf = ByteBuffer.wrap(payload);

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload(buf));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload(clone(buf)));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        try (Fuzzer fuzzer = new Fuzzer(this))
        {
            fuzzer.connect();
            fuzzer.setSendMode(SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
        }
    }

    /**
     * Echo 65535 byte TEXT message (uses medium 2 byte payload length)
     * @throws Exception on test failure
     */
    @Test
    public void testCase1_1_6() throws Exception
    {
        byte payload[] = new byte[65535];
        Arrays.fill(payload,(byte)'*');
        ByteBuffer buf = ByteBuffer.wrap(payload);

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload(buf));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload(clone(buf)));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        try (Fuzzer fuzzer = new Fuzzer(this))
        {
            fuzzer.connect();
            fuzzer.setSendMode(SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
        }
    }

    /**
     * Echo 65536 byte TEXT message (uses large 8 byte payload length)
     * @throws Exception on test failure
     */
    @Test
    public void testCase1_1_7() throws Exception
    {
        byte payload[] = new byte[65536];
        Arrays.fill(payload,(byte)'*');
        ByteBuffer buf = ByteBuffer.wrap(payload);

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload(buf));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload(clone(buf)));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        try (Fuzzer fuzzer = new Fuzzer(this))
        {
            fuzzer.connect();
            fuzzer.setSendMode(SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
        }
    }

    /**
     * Echo 65536 byte TEXT message (uses large 8 byte payload length).
     * <p>
     * Only send 1 TEXT frame from client, but in small segments (flushed after each).
     * <p>
     * This is done to test the parsing together of the frame on the server side.
     * @throws Exception on test failure
     */
    @Test
    public void testCase1_1_8() throws Exception
    {
        byte payload[] = new byte[65536];
        Arrays.fill(payload,(byte)'*');
        ByteBuffer buf = ByteBuffer.wrap(payload);
        int segmentSize = 997;

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload(buf));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload(clone(buf)));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        try (Fuzzer fuzzer = new Fuzzer(this))
        {
            fuzzer.connect();
            fuzzer.setSendMode(SendMode.SLOW);
            fuzzer.setSlowSendSegmentSize(segmentSize);
            fuzzer.send(send);
            fuzzer.expect(expect);
        }
    }

    /**
     * Echo 0 byte BINARY message
     * @throws Exception on test failure
     */
    @Test
    public void testCase1_2_1() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new BinaryFrame());
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new BinaryFrame());
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        try (Fuzzer fuzzer = new Fuzzer(this))
        {
            fuzzer.connect();
            fuzzer.setSendMode(SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
        }
    }

    /**
     * Echo 125 byte BINARY message (uses small 7-bit payload length)
     * @throws Exception on test failure
     */
    @Test
    public void testCase1_2_2() throws Exception
    {
        byte payload[] = new byte[125];
        Arrays.fill(payload,(byte)0xFE);
        ByteBuffer buf = ByteBuffer.wrap(payload);

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new BinaryFrame().setPayload(buf));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new BinaryFrame().setPayload(clone(buf)));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        try (Fuzzer fuzzer = new Fuzzer(this))
        {
            fuzzer.connect();
            fuzzer.setSendMode(SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
        }
    }

    /**
     * Echo 126 byte BINARY message (uses medium 2 byte payload length)
     * @throws Exception on test failure
     */
    @Test
    public void testCase1_2_3() throws Exception
    {
        byte payload[] = new byte[126];
        Arrays.fill(payload,(byte)0xFE);
        ByteBuffer buf = ByteBuffer.wrap(payload);

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new BinaryFrame().setPayload(buf));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new BinaryFrame().setPayload(clone(buf)));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        try (Fuzzer fuzzer = new Fuzzer(this))
        {
            fuzzer.connect();
            fuzzer.setSendMode(SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
        }
    }

    /**
     * Echo 127 byte BINARY message (uses medium 2 byte payload length)
     * @throws Exception on test failure
     */
    @Test
    public void testCase1_2_4() throws Exception
    {
        byte payload[] = new byte[127];
        Arrays.fill(payload,(byte)0xFE);
        ByteBuffer buf = ByteBuffer.wrap(payload);

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new BinaryFrame().setPayload(buf));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new BinaryFrame().setPayload(clone(buf)));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        try (Fuzzer fuzzer = new Fuzzer(this))
        {
            fuzzer.connect();
            fuzzer.setSendMode(SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
        }
    }

    /**
     * Echo 128 byte BINARY message (uses medium 2 byte payload length)
     * @throws Exception on test failure
     */
    @Test
    public void testCase1_2_5() throws Exception
    {
        byte payload[] = new byte[128];
        Arrays.fill(payload,(byte)0xFE);
        ByteBuffer buf = ByteBuffer.wrap(payload);

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new BinaryFrame().setPayload(buf));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new BinaryFrame().setPayload(clone(buf)));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        try (Fuzzer fuzzer = new Fuzzer(this))
        {
            fuzzer.connect();
            fuzzer.setSendMode(SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
        }
    }

    /**
     * Echo 65535 byte BINARY message (uses medium 2 byte payload length)
     * @throws Exception on test failure
     */
    @Test
    public void testCase1_2_6() throws Exception
    {
        byte payload[] = new byte[65535];
        Arrays.fill(payload,(byte)0xFE);
        ByteBuffer buf = ByteBuffer.wrap(payload);

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new BinaryFrame().setPayload(buf));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new BinaryFrame().setPayload(clone(buf)));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        try (Fuzzer fuzzer = new Fuzzer(this))
        {
            fuzzer.connect();
            fuzzer.setSendMode(SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
        }
    }

    /**
     * Echo 65536 byte BINARY message (uses large 8 byte payload length)
     * @throws Exception on test failure
     */
    @Test
    public void testCase1_2_7() throws Exception
    {
        byte payload[] = new byte[65536];
        Arrays.fill(payload,(byte)0xFE);
        ByteBuffer buf = ByteBuffer.wrap(payload);

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new BinaryFrame().setPayload(buf));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new BinaryFrame().setPayload(clone(buf)));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        try (Fuzzer fuzzer = new Fuzzer(this))
        {
            fuzzer.connect();
            fuzzer.setSendMode(SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
        }
    }

    /**
     * Echo 65536 byte BINARY message (uses large 8 byte payload length).
     * <p>
     * Only send 1 BINARY frame from client, but in small segments (flushed after each).
     * <p>
     * This is done to test the parsing together of the frame on the server side.
     * @throws Exception on test failure
     */
    @Test
    public void testCase1_2_8() throws Exception
    {
        byte payload[] = new byte[65536];
        Arrays.fill(payload,(byte)0xFE);
        ByteBuffer buf = ByteBuffer.wrap(payload);
        int segmentSize = 997;

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new BinaryFrame().setPayload(buf));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new BinaryFrame().setPayload(clone(buf)));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        try (Fuzzer fuzzer = new Fuzzer(this))
        {
            fuzzer.connect();
            fuzzer.setSendMode(SendMode.SLOW);
            fuzzer.setSlowSendSegmentSize(segmentSize);
            fuzzer.send(send);
            fuzzer.expect(expect);
        }
    }
}
