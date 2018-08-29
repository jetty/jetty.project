//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.tests.server;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Parser;
import org.eclipse.jetty.websocket.core.frames.Frame;
import org.eclipse.jetty.websocket.core.frames.OpCode;
import org.eclipse.jetty.websocket.tests.BadFrame;
import org.eclipse.jetty.websocket.tests.DataUtils;
import org.eclipse.jetty.websocket.tests.Fuzzer;
import org.eclipse.jetty.websocket.tests.server.servlets.EchoSocket;
import org.junit.Test;

public class PingPongTest extends AbstractLocalServerCase
{
    /**
     * 10 pings
     * <p>
     * From Autobahn WebSocket Server Testcase 2.10
     * </p>
     *
     * @throws Exception on test failure
     */
    @Test
    public void testPing_10Unique() throws Exception
    {
        // send 10 pings each with unique payload
        // send close
        // expect 10 pongs with our unique payload
        // expect close
        
        int pingCount = 10;
        
        List<Frame> send = new ArrayList<>();
        List<Frame> expect = new ArrayList<>();
        
        for (int i = 0; i < pingCount; i++)
        {
            String payload = String.format("ping-%d[%X]", i, i);
            send.add(new Frame(OpCode.PING).setPayload(payload));
            expect.add(new Frame(OpCode.PONG).setPayload(payload));
        }
        send.add(CloseStatus.toFrame(StatusCode.NORMAL.getCode()));
        expect.add(CloseStatus.toFrame(StatusCode.NORMAL.getCode()));
    
        try (Fuzzer session = server.newNetworkFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
    
    /**
     * 10 pings, sent slowly
     * <p>
     * From Autobahn WebSocket Server Testcase 2.11
     * </p>
     *
     * @throws Exception on test failure
     */
    @Test
    public void testPing_10UniqueSlowly() throws Exception
    {
        // send 10 pings (slowly) each with unique payload
        // send close
        // expect 10 pongs with OUR payload
        // expect close
        
        int pingCount = 10;
        
        List<Frame> send = new ArrayList<>();
        List<Frame> expect = new ArrayList<>();
        
        for (int i = 0; i < pingCount; i++)
        {
            String payload = String.format("ping-%d[%X]", i, i);
            send.add(new Frame(OpCode.PING).setPayload(payload));
            expect.add(new Frame(OpCode.PONG).setPayload(payload));
        }
        send.add(CloseStatus.toFrame(StatusCode.NORMAL.getCode()));
        expect.add(CloseStatus.toFrame(StatusCode.NORMAL.getCode()));
    
        try (Fuzzer session = server.newNetworkFuzzer())
        {
            session.sendSegmented(send, 5);
            session.expect(expect);
        }
    }
    
    /**
     * Ping with 125 byte binary payload
     * <p>
     * From Autobahn WebSocket Server Testcase 2.4
     * </p>
     *
     * @throws Exception on test failure
     */
    @Test
    public void testPing_MaxBinaryPayload() throws Exception
    {
        byte payload[] = new byte[125];
        Arrays.fill(payload, (byte) 0xFE);
        
        List<Frame> send = new ArrayList<>();
        send.add(new Frame(OpCode.PING).setPayload(payload));
        send.add(CloseStatus.toFrame(StatusCode.NORMAL.getCode()));
        
        List<Frame> expect = new ArrayList<>();
        expect.add(new Frame(OpCode.PONG).setPayload(DataUtils.copyOf(payload)));
        expect.add(CloseStatus.toFrame(StatusCode.NORMAL.getCode()));
    
        try (Fuzzer session = server.newNetworkFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
    
    /**
     * Ping with 125 byte binary payload (slow send)
     * <p>
     * From Autobahn WebSocket Server Testcase 2.6
     * </p>
     *
     * @throws Exception on test failure
     */
    @Test
    public void testPing_MaxBinaryPayloadSlow() throws Exception
    {
        byte payload[] = new byte[125];
        Arrays.fill(payload, (byte) '6');
        
        List<Frame> send = new ArrayList<>();
        send.add(new Frame(OpCode.PING).setPayload(payload));
        send.add(CloseStatus.toFrame(StatusCode.NORMAL.getCode(), "Test 2.6"));
        
        List<Frame> expect = new ArrayList<>();
        expect.add(new Frame(OpCode.PONG).setPayload(DataUtils.copyOf(payload)));
        expect.add(CloseStatus.toFrame(StatusCode.NORMAL.getCode(), "Test 2.6"));
    
        try (Fuzzer session = server.newNetworkFuzzer())
        {
            session.sendSegmented(send, 1);
            session.expect(expect);
        }
    }
    
    /**
     * Ping without payload.
     * <p>
     * From Autobahn WebSocket Server Testcase 2.1
     * </p>
     *
     * @throws Exception on test failure
     */
    @Test
    public void testPing_NoPayload() throws Exception
    {
        List<Frame> send = new ArrayList<>();
        send.add(new Frame(OpCode.PING));
        send.add(CloseStatus.toFrame(StatusCode.NORMAL.getCode()));
        
        List<Frame> expect = new ArrayList<>();
        expect.add(new Frame(OpCode.PONG));
        expect.add(CloseStatus.toFrame(StatusCode.NORMAL.getCode()));
    
        try (Fuzzer session = server.newNetworkFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
    
    /**
     * Ping with 126 byte payload
     * <p>
     * From Autobahn WebSocket Server Testcase 2.5
     * </p>
     *
     * @throws Exception on test failure
     */
    @Test
    public void testPing_OverSizedPayload() throws Exception
    {
        try (StacklessLogging ignored = new StacklessLogging(Parser.class, EchoSocket.class))
        {
            byte payload[] = new byte[126]; // intentionally too big
            Arrays.fill(payload, (byte) '5');
            ByteBuffer buf = ByteBuffer.wrap(payload);
            
            List<Frame> send = new ArrayList<>();
            // trick websocket frame into making extra large payload for ping
            send.add(new BadFrame(OpCode.PING).setPayload(buf));
            send.add(CloseStatus.toFrame(StatusCode.NORMAL.getCode(), "Test 2.5"));
            
            List<Frame> expect = new ArrayList<>();
            expect.add(CloseStatus.toFrame(StatusCode.PROTOCOL.getCode()));
    
            try (Fuzzer session = server.newNetworkFuzzer())
            {
                session.sendBulk(send);
                session.expect(expect);
            }
        }
    }
    
    /**
     * Ping with small binary (non-utf8) payload
     * <p>
     * From Autobahn WebSocket Server Testcase 2.3
     * </p>
     *
     * @throws Exception on test failure
     */
    @Test
    public void testPing_SmallBinaryPayload() throws Exception
    {
        byte payload[] = new byte[]{0x00, (byte) 0xFF, (byte) 0xFE, (byte) 0xFD, (byte) 0xFC, (byte) 0xFB, 0x00, (byte) 0xFF};
        
        List<Frame> send = new ArrayList<>();
        send.add(new Frame(OpCode.PING).setPayload(payload));
        send.add(CloseStatus.toFrame(StatusCode.NORMAL.getCode()));
        
        List<Frame> expect = new ArrayList<>();
        expect.add(new Frame(OpCode.PONG).setPayload(DataUtils.copyOf(payload)));
        expect.add(CloseStatus.toFrame(StatusCode.NORMAL.getCode()));
    
        try (Fuzzer session = server.newNetworkFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
    
    /**
     * Ping with small text payload
     * <p>
     * From Autobahn WebSocket Server Testcase 2.2
     * </p>
     *
     * @throws Exception on test failure
     */
    @Test
    public void testPing_SmallTextPayload() throws Exception
    {
        byte payload[] = StringUtil.getUtf8Bytes("Hello world");
        
        List<Frame> send = new ArrayList<>();
        send.add(new Frame(OpCode.PING).setPayload(payload));
        send.add(CloseStatus.toFrame(StatusCode.NORMAL.getCode()));
        
        List<Frame> expect = new ArrayList<>();
        expect.add(new Frame(OpCode.PONG).setPayload(DataUtils.copyOf(payload)));
        expect.add(CloseStatus.toFrame(StatusCode.NORMAL.getCode()));
    
        try (Fuzzer session = server.newNetworkFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
    
    /**
     * Unsolicited pong frame without payload
     * <p>
     * From Autobahn WebSocket Server Testcase 2.10
     * </p>
     *
     * @throws Exception on test failure
     */
    @Test
    public void testPong_Unsolicited() throws Exception
    {
        List<Frame> send = new ArrayList<>();
        send.add(new Frame(OpCode.PONG)); // unsolicited pong
        send.add(CloseStatus.toFrame(StatusCode.NORMAL.getCode()));
        
        List<Frame> expect = new ArrayList<>();
        expect.add(CloseStatus.toFrame(StatusCode.NORMAL.getCode()));
    
        try (Fuzzer session = server.newNetworkFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
    
    /**
     * Unsolicited pong frame with basic payload
     * <p>
     * From Autobahn WebSocket Server Testcase 2.8
     * </p>
     *
     * @throws Exception on test failure
     */
    @Test
    public void testPong_UnsolicitedWithPayload() throws Exception
    {
        List<Frame> send = new ArrayList<>();
        send.add(new Frame(OpCode.PONG).setPayload("unsolicited")); // unsolicited pong
        send.add(CloseStatus.toFrame(StatusCode.NORMAL.getCode()));
        
        List<Frame> expect = new ArrayList<>();
        expect.add(CloseStatus.toFrame(StatusCode.NORMAL.getCode()));
    
        try (Fuzzer session = server.newNetworkFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
    
    /**
     * Unsolicited pong frame, then ping with basic payload
     * <p>
     * From Autobahn WebSocket Server Testcase 2.9
     * </p>
     *
     * @throws Exception on test failure
     */
    @Test
    public void testPong_Unsolicited_Ping() throws Exception
    {
        List<Frame> send = new ArrayList<>();
        send.add(new Frame(OpCode.PONG).setPayload("unsolicited")); // unsolicited pong
        send.add(new Frame(OpCode.PING).setPayload("our ping")); // our ping
        send.add(CloseStatus.toFrame(StatusCode.NORMAL.getCode()));
        
        List<Frame> expect = new ArrayList<>();
        expect.add(new Frame(OpCode.PONG).setPayload("our ping")); // our pong
        expect.add(CloseStatus.toFrame(StatusCode.NORMAL.getCode()));
    
        try (Fuzzer session = server.newNetworkFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
}
