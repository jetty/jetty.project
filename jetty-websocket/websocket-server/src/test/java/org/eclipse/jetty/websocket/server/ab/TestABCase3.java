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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.jetty.toolchain.test.AdvancedRunner;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.Parser;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.frames.BinaryFrame;
import org.eclipse.jetty.websocket.common.frames.PingFrame;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.common.test.Fuzzer;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Test various RSV violations
 */
@RunWith(AdvancedRunner.class)
public class TestABCase3 extends AbstractABCase
{
    /**
     * Send small text frame, with RSV1 == true, with no extensions defined.
     * @throws Exception on test failure
     */
    @Test
    public void testCase3_1() throws Exception
    {
        WebSocketFrame send = new TextFrame().setPayload("small").setRsv1(true); // intentionally bad

        WebSocketFrame expect = new CloseInfo(StatusCode.PROTOCOL).asFrame();

        try (Fuzzer fuzzer = new Fuzzer(this); StacklessLogging logging = new StacklessLogging(Parser.class))
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
        }
    }

    /**
     * Send small text frame, send again with RSV2 == true, then ping, with no extensions defined.
     * @throws Exception on test failure
     */
    @Test
    public void testCase3_2() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload("small"));
        send.add(new TextFrame().setPayload("small").setRsv2(true)); // intentionally bad
        send.add(new PingFrame().setPayload("ping"));

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload("small")); // echo on good frame
        expect.add(new CloseInfo(StatusCode.PROTOCOL).asFrame());

        try (Fuzzer fuzzer = new Fuzzer(this); StacklessLogging logging = new StacklessLogging(Parser.class))
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
        }
    }

    /**
     * Send small text frame, send again with (RSV1 & RSV2), then ping, with no extensions defined.
     * @throws Exception on test failure
     */
    @Test
    public void testCase3_3() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload("small"));
        send.add(new TextFrame().setPayload("small").setRsv1(true).setRsv2(true)); // intentionally bad
        send.add(new PingFrame().setPayload("ping"));

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload("small")); // echo on good frame
        expect.add(new CloseInfo(StatusCode.PROTOCOL).asFrame());

        try (Fuzzer fuzzer = new Fuzzer(this); StacklessLogging logging = new StacklessLogging(Parser.class))
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.PER_FRAME);
            fuzzer.send(send);
            fuzzer.expect(expect);
        }
    }

    /**
     * Send small text frame, send again with (RSV3), then ping, with no extensions defined.
     * @throws Exception on test failure
     */
    @Test
    public void testCase3_4() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload("small"));
        send.add(new TextFrame().setPayload("small").setRsv3(true)); // intentionally bad
        send.add(new PingFrame().setPayload("ping"));

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload("small")); // echo on good frame
        expect.add(new CloseInfo(StatusCode.PROTOCOL).asFrame());

        try (Fuzzer fuzzer = new Fuzzer(this); StacklessLogging logging = new StacklessLogging(Parser.class))
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.SLOW);
            fuzzer.setSlowSendSegmentSize(1);
            fuzzer.send(send);
            fuzzer.expect(expect);
        }
    }

    /**
     * Send binary frame with (RSV3 & RSV1), with no extensions defined.
     * @throws Exception on test failure
     */
    @Test
    public void testCase3_5() throws Exception
    {
        byte payload[] = new byte[8];
        Arrays.fill(payload,(byte)0xFF);

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new BinaryFrame().setPayload(payload).setRsv3(true).setRsv1(true)); // intentionally bad

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseInfo(StatusCode.PROTOCOL).asFrame());

        try (Fuzzer fuzzer = new Fuzzer(this); StacklessLogging logging = new StacklessLogging(Parser.class))
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
        }
    }

    /**
     * Send ping frame with (RSV3 & RSV2), with no extensions defined.
     * @throws Exception on test failure
     */
    @Test
    public void testCase3_6() throws Exception
    {
        byte payload[] = new byte[8];
        Arrays.fill(payload,(byte)0xFF);

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new PingFrame().setPayload(payload).setRsv3(true).setRsv2(true)); // intentionally bad

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseInfo(StatusCode.PROTOCOL).asFrame());

        try (Fuzzer fuzzer = new Fuzzer(this); StacklessLogging logging = new StacklessLogging(Parser.class))
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
        }
    }

    /**
     * Send close frame with (RSV3 & RSV2 & RSV1), with no extensions defined.
     * @throws Exception on test failure
     */
    @Test
    public void testCase3_7() throws Exception
    {
        byte payload[] = new byte[8];
        Arrays.fill(payload,(byte)0xFF);

        List<WebSocketFrame> send = new ArrayList<>();
        WebSocketFrame frame = new CloseInfo(StatusCode.NORMAL).asFrame();
        frame.setRsv1(true);
        frame.setRsv2(true);
        frame.setRsv3(true);
        send.add(frame); // intentionally bad

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseInfo(StatusCode.PROTOCOL).asFrame());

        try (Fuzzer fuzzer = new Fuzzer(this); StacklessLogging logging = new StacklessLogging(Parser.class))
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
        }
    }
}
