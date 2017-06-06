//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.toolchain.test.Hex;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.Parser;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.frames.CloseFrame;
import org.eclipse.jetty.websocket.common.frames.ContinuationFrame;
import org.eclipse.jetty.websocket.common.frames.PingFrame;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.common.io.AbstractWebSocketConnection;
import org.eclipse.jetty.websocket.tests.BadFrame;
import org.eclipse.jetty.websocket.tests.DataUtils;
import org.eclipse.jetty.websocket.tests.LocalFuzzer;
import org.junit.Test;

/**
 * Test of Close Handling
 */
public class CloseHandlingTest extends AbstractLocalServerCase
{
    /**
     * close with invalid payload (payload length 1)
     * <p>
     * From Autobahn WebSocket Server Testcase 7.3.2
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testClose_1BytePayload() throws Exception
    {
        byte payload[] = new byte[] { 0x00 };
        ByteBuffer buf = ByteBuffer.wrap(payload);

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new CloseFrame().setPayload(buf));

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseInfo(StatusCode.PROTOCOL).asFrame());
    
        try (StacklessLogging ignored = new StacklessLogging(Parser.class);
             LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }

    /**
     * close with invalid UTF8 in payload
     * <p>
     * From Autobahn WebSocket Server Testcase 7.5.1
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testClose_BadUtf8Reason() throws Exception
    {
        ByteBuffer payload = ByteBuffer.allocate(256);
        BufferUtil.clearToFill(payload);
        payload.put((byte)0x03); // normal close
        payload.put((byte)0xE8);
        byte invalidUtf[] = Hex.asByteArray("CEBAE1BDB9CF83CEBCCEB5EDA080656469746564");
        payload.put(invalidUtf);
        BufferUtil.flipToFlush(payload,0);

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new BadFrame(OpCode.CLOSE).setPayload(payload)); // intentionally bad payload

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseInfo(StatusCode.BAD_PAYLOAD).asFrame());
    
        try (StacklessLogging ignored = new StacklessLogging(Parser.class,CloseInfo.class);
             LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }

    /**
     * close with no payload (payload length 0)
     * <p>
     * From Autobahn WebSocket Server Testcase 7.3.1
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testClose_Empty() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new CloseFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseFrame());
    
        try (LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }

    /**
     * close with valid payload (with 123 byte reason)
     * <p>
     * From Autobahn WebSocket Server Testcase 7.3.5
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testClose_MaxReasonLength() throws Exception
    {
        byte utf[] = new byte[123];
        Arrays.fill(utf,(byte)'!');
        String reason = StringUtil.toUTF8String(utf,0,utf.length);

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new CloseInfo(StatusCode.NORMAL,reason).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseInfo(StatusCode.NORMAL,reason).asFrame());
    
        try (StacklessLogging ignored = new StacklessLogging(AbstractWebSocketConnection.class);
             LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }

    /**
     * Basic message then close frame, normal behavior
     * <p>
     * From Autobahn WebSocket Server Testcase 7.1.1
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testClose_Normal() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload("Hello World"));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload("Hello World"));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        try (LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }

    /**
     * Close frame, then ping frame (no pong received)
     * <p>
     * From Autobahn WebSocket Server Testcase 7.1.3
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testClose_Ping() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());
        send.add(new PingFrame().setPayload("out of band ping"));

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        try (LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }

    /**
     * close with valid payload (payload length 2)
     * <p>
     * From Autobahn WebSocket Server Testcase 7.3.3
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testClose_StatusCode() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        try (LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }

    /**
     * close with valid payload (with reason)
     * <p>
     * From Autobahn WebSocket Server Testcase 7.3.4
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testClose_StatusCode_Reason() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new CloseInfo(StatusCode.NORMAL,"Hic").asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseInfo(StatusCode.NORMAL,"Hic").asFrame());
    
        try (LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }

    /**
     * Close frame, then ping frame (no pong received)
     * <p>
     * From Autobahn WebSocket Server Testcase 7.1.4
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testClose_Text() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());
        send.add(new TextFrame().setPayload("out of band text"));

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        try (LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }

    /**
     * Close frame, then another close frame (send frame ignored)
     * <p>
     * From Autobahn WebSocket Server Testcase 7.1.2
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testClose_Twice() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());
        send.add(new CloseInfo().asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        try (LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }

    /**
     * Text fin=false, close, then continuation fin=true
     * <p>
     * From Autobahn WebSocket Server Testcase 7.1.5
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testTextNoFin_Close_ContinuationFin() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload("an").setFin(false));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());
        send.add(new ContinuationFrame().setPayload("ticipation").setFin(true));

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        try (LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }

    /**
     * 256k msg, then close, then ping
     * <p>
     * From Autobahn WebSocket Server Testcase 7.1.6
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testText_Close_Ping() throws Exception
    {
        byte msg[] = new byte[256 * 1024];
        Arrays.fill(msg,(byte)'*');
        ByteBuffer buf = ByteBuffer.wrap(msg);

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload(buf));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());
        send.add(new PingFrame().setPayload("out of band"));

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload(DataUtils.copyOf(buf)));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        try (LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
}
