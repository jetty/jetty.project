//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
import org.junit.jupiter.api.Test;

public class TestABCase1 extends AbstractABCase
{
    /**
     * Echo 0 byte TEXT message
     *
     * @throws Exception on test failure
     */
    @Test
    public void testCase111() throws Exception
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
     *
     * @throws Exception on test failure
     */
    @Test
    public void testCase112() throws Exception
    {
        byte[] payload = new byte[125];
        Arrays.fill(payload, (byte)'*');
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
     *
     * @throws Exception on test failure
     */
    @Test
    public void testCase113() throws Exception
    {
        byte[] payload = new byte[126];
        Arrays.fill(payload, (byte)'*');
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
     *
     * @throws Exception on test failure
     */
    @Test
    public void testCase114() throws Exception
    {
        byte[] payload = new byte[127];
        Arrays.fill(payload, (byte)'*');
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
     *
     * @throws Exception on test failure
     */
    @Test
    public void testCase115() throws Exception
    {
        byte[] payload = new byte[128];
        Arrays.fill(payload, (byte)'*');
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
     *
     * @throws Exception on test failure
     */
    @Test
    public void testCase116() throws Exception
    {
        byte[] payload = new byte[65535];
        Arrays.fill(payload, (byte)'*');
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
     *
     * @throws Exception on test failure
     */
    @Test
    public void testCase117() throws Exception
    {
        byte[] payload = new byte[65536];
        Arrays.fill(payload, (byte)'*');
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
     *
     * @throws Exception on test failure
     */
    @Test
    public void testCase118() throws Exception
    {
        byte[] payload = new byte[65536];
        Arrays.fill(payload, (byte)'*');
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
     *
     * @throws Exception on test failure
     */
    @Test
    public void testCase121() throws Exception
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
     *
     * @throws Exception on test failure
     */
    @Test
    public void testCase122() throws Exception
    {
        byte[] payload = new byte[125];
        Arrays.fill(payload, (byte)0xFE);
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
     *
     * @throws Exception on test failure
     */
    @Test
    public void testCase123() throws Exception
    {
        byte[] payload = new byte[126];
        Arrays.fill(payload, (byte)0xFE);
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
     *
     * @throws Exception on test failure
     */
    @Test
    public void testCase124() throws Exception
    {
        byte[] payload = new byte[127];
        Arrays.fill(payload, (byte)0xFE);
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
     *
     * @throws Exception on test failure
     */
    @Test
    public void testCase125() throws Exception
    {
        byte[] payload = new byte[128];
        Arrays.fill(payload, (byte)0xFE);
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
     *
     * @throws Exception on test failure
     */
    @Test
    public void testCase126() throws Exception
    {
        byte[] payload = new byte[65535];
        Arrays.fill(payload, (byte)0xFE);
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
     *
     * @throws Exception on test failure
     */
    @Test
    public void testCase127() throws Exception
    {
        byte[] payload = new byte[65536];
        Arrays.fill(payload, (byte)0xFE);
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
     *
     * @throws Exception on test failure
     */
    @Test
    public void testCase128() throws Exception
    {
        byte[] payload = new byte[65536];
        Arrays.fill(payload, (byte)0xFE);
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
