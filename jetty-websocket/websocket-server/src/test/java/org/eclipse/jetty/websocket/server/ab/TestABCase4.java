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
import java.util.List;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.Parser;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.frames.PingFrame;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.common.test.Fuzzer;
import org.junit.jupiter.api.Test;

/**
 * Test various bad / forbidden opcodes (per spec)
 */
public class TestABCase4 extends AbstractABCase
{
    /**
     * Send opcode 3 (reserved)
     *
     * @throws Exception on test failure
     */
    @Test
    public void testCase411() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new BadFrame((byte)3)); // intentionally bad

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseInfo(StatusCode.PROTOCOL).asFrame());

        try (Fuzzer fuzzer = new Fuzzer(this);
             StacklessLogging logging = new StacklessLogging(Parser.class))
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
        }
    }

    /**
     * Send opcode 4 (reserved), with payload
     *
     * @throws Exception on test failure
     */
    @Test
    public void testCase412() throws Exception
    {
        byte[] payload = StringUtil.getUtf8Bytes("reserved payload");
        ByteBuffer buf = ByteBuffer.wrap(payload);

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new BadFrame((byte)4).setPayload(buf)); // intentionally bad

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseInfo(StatusCode.PROTOCOL).asFrame());

        try (Fuzzer fuzzer = new Fuzzer(this);
             StacklessLogging logging = new StacklessLogging(Parser.class))
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
        }
    }

    /**
     * Send small text, then frame with opcode 5 (reserved), then ping
     *
     * @throws Exception on test failure
     */
    @Test
    public void testCase413() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload("hello"));
        send.add(new BadFrame((byte)5)); // intentionally bad
        send.add(new PingFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload("hello")); // echo
        expect.add(new CloseInfo(StatusCode.PROTOCOL).asFrame());

        try (Fuzzer fuzzer = new Fuzzer(this);
             StacklessLogging logging = new StacklessLogging(Parser.class))
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
        }
    }

    /**
     * Send small text, then frame with opcode 6 (reserved) w/payload, then ping
     *
     * @throws Exception on test failure
     */
    @Test
    public void testCase414() throws Exception
    {
        ByteBuffer buf = ByteBuffer.wrap(StringUtil.getUtf8Bytes("bad"));

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload("hello"));
        send.add(new BadFrame((byte)6).setPayload(buf)); // intentionally bad
        send.add(new PingFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload("hello")); // echo
        expect.add(new CloseInfo(StatusCode.PROTOCOL).asFrame());

        try (Fuzzer fuzzer = new Fuzzer(this);
             StacklessLogging logging = new StacklessLogging(Parser.class))
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
        }
    }

    /**
     * Send small text, then frame with opcode 7 (reserved) w/payload, then ping
     *
     * @throws Exception on test failure
     */
    @Test
    public void testCase415() throws Exception
    {
        ByteBuffer buf = ByteBuffer.wrap(StringUtil.getUtf8Bytes("bad"));

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload("hello"));
        send.add(new BadFrame((byte)7).setPayload(buf)); // intentionally bad
        send.add(new PingFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload("hello")); // echo
        expect.add(new CloseInfo(StatusCode.PROTOCOL).asFrame());

        try (Fuzzer fuzzer = new Fuzzer(this);
             StacklessLogging logging = new StacklessLogging(Parser.class))
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
        }
    }

    /**
     * Send opcode 11 (reserved)
     *
     * @throws Exception on test failure
     */
    @Test
    public void testCase421() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new BadFrame((byte)11)); // intentionally bad

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseInfo(StatusCode.PROTOCOL).asFrame());

        try (Fuzzer fuzzer = new Fuzzer(this);
             StacklessLogging logging = new StacklessLogging(Parser.class))
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
        }
    }

    /**
     * Send opcode 12 (reserved)
     *
     * @throws Exception on test failure
     */
    @Test
    public void testCase422() throws Exception
    {
        ByteBuffer buf = ByteBuffer.wrap(StringUtil.getUtf8Bytes("bad"));

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new BadFrame((byte)12).setPayload(buf)); // intentionally bad

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseInfo(StatusCode.PROTOCOL).asFrame());

        try (Fuzzer fuzzer = new Fuzzer(this);
             StacklessLogging logging = new StacklessLogging(Parser.class))
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
        }
    }

    /**
     * Send small text, then frame with opcode 13 (reserved), then ping
     *
     * @throws Exception on test failure
     */
    @Test
    public void testCase423() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload("hello"));
        send.add(new BadFrame((byte)13)); // intentionally bad
        send.add(new PingFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload("hello")); // echo
        expect.add(new CloseInfo(StatusCode.PROTOCOL).asFrame());

        try (Fuzzer fuzzer = new Fuzzer(this);
             StacklessLogging logging = new StacklessLogging(Parser.class))
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
        }
    }

    /**
     * Send small text, then frame with opcode 14 (reserved), then ping
     *
     * @throws Exception on test failure
     */
    @Test
    public void testCase424() throws Exception
    {
        ByteBuffer buf = ByteBuffer.wrap(StringUtil.getUtf8Bytes("bad"));

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload("hello"));
        send.add(new BadFrame((byte)14).setPayload(buf)); // intentionally bad
        send.add(new PingFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload("hello")); // echo
        expect.add(new CloseInfo(StatusCode.PROTOCOL).asFrame());

        try (Fuzzer fuzzer = new Fuzzer(this);
             StacklessLogging logging = new StacklessLogging(Parser.class))
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
        }
    }

    /**
     * Send small text, then frame with opcode 15 (reserved), then ping
     *
     * @throws Exception on test failure
     */
    @Test
    public void testCase425() throws Exception
    {
        ByteBuffer buf = ByteBuffer.wrap(StringUtil.getUtf8Bytes("bad"));

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload("hello"));
        send.add(new BadFrame((byte)15).setPayload(buf)); // intentionally bad
        send.add(new PingFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload("hello")); // echo
        expect.add(new CloseInfo(StatusCode.PROTOCOL).asFrame());

        try (Fuzzer fuzzer = new Fuzzer(this);
             StacklessLogging logging = new StacklessLogging(Parser.class))
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
        }
    }
}
