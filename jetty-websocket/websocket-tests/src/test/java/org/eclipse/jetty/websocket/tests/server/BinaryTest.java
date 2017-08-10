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

import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.core.CloseInfo;
import org.eclipse.jetty.websocket.core.frames.OpCode;
import org.eclipse.jetty.websocket.core.WebSocketFrame;
import org.eclipse.jetty.websocket.core.frames.BinaryFrame;
import org.eclipse.jetty.websocket.tests.DataUtils;
import org.eclipse.jetty.websocket.tests.LocalFuzzer;
import org.junit.Test;

/**
 * Binary message / frame tests
 */
public class BinaryTest extends AbstractLocalServerCase
{
    /**
     * Echo 16MB binary message (1 frame)
     * <p>
     * From Autobahn WebSocket Server Testcase 9.2.6
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testBinary_16mb_SingleFrame() throws Exception
    {
        byte data[] = new byte[16 * MBYTE];
        Arrays.fill(data,(byte)0x26);
        ByteBuffer buf = ByteBuffer.wrap(data);

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new BinaryFrame().setPayload(buf));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new BinaryFrame().setPayload(DataUtils.copyOf(buf)));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        try(LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }

    /**
     * Echo 1MB binary message (1 frame)
     * <p>
     * From Autobahn WebSocket Server Testcase 9.2.3
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testBinary_1mb_SingleFrame() throws Exception
    {
        byte data[] = new byte[1 * MBYTE];
        Arrays.fill(data,(byte)0x23);
        ByteBuffer buf = ByteBuffer.wrap(data);

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new BinaryFrame().setPayload(buf));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new BinaryFrame().setPayload(DataUtils.copyOf(buf)));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        try(LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }

    /**
     * Echo 256KB binary message (1 frame)
     * <p>
     * From Autobahn WebSocket Server Testcase 9.2.2
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testBinary_256kb_SingleFrame() throws Exception
    {
        byte data[] = new byte[256 * KBYTE];
        Arrays.fill(data,(byte)0x22);
        ByteBuffer buf = ByteBuffer.wrap(data);

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new BinaryFrame().setPayload(buf));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new BinaryFrame().setPayload(DataUtils.copyOf(buf)));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        try(LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }

    /**
     * Send 4MB binary message in multiple frames.
     * <p>
     * From Autobahn WebSocket Server Testcase 9.4.5
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testBinary_4mb_Frames_16kb() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        ByteBuffer payload = newMultiFrameMessage(send, OpCode.BINARY, 4 * MBYTE, 16 * KBYTE);
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new BinaryFrame().setPayload(payload));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        try(LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }

    /**
     * Send 4MB binary message in multiple frames.
     * <p>
     * From Autobahn WebSocket Server Testcase 9.4.3
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testBinary_4mb_Frames_1kb() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        ByteBuffer payload = newMultiFrameMessage(send, OpCode.BINARY, 4 * MBYTE, 1 * KBYTE);
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new BinaryFrame().setPayload(payload));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        try(LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }

    /**
     * Send 4MB binary message in multiple frames.
     * <p>
     * From Autobahn WebSocket Server Testcase 9.4.8
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testBinary_4mb_Frames_1mb() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        ByteBuffer payload = newMultiFrameMessage(send, OpCode.BINARY, 4 * MBYTE, 1 * MBYTE);
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new BinaryFrame().setPayload(payload));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        try(LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
    
    /**
     * Send 4MB binary message in multiple frames.
     * <p>
     * From Autobahn WebSocket Server Testcase 9.4.2
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testBinary_4mb_Frames_256b() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        ByteBuffer payload = newMultiFrameMessage(send, OpCode.BINARY, 4 * MBYTE, 256);
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new BinaryFrame().setPayload(payload));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        try(LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }

    /**
     * Send 4MB binary message in multiple frames.
     * <p>
     * From Autobahn WebSocket Server Testcase 9.4.7
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testBinary_4mb_Frames_256kb() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        ByteBuffer payload = newMultiFrameMessage(send, OpCode.BINARY, 4 * MBYTE, 256 * KBYTE);
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new BinaryFrame().setPayload(payload));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        try(LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }

    /**
     * Send 4MB binary message in multiple frames.
     * <p>
     * From Autobahn WebSocket Server Testcase 9.4.4
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testBinary_4mb_Frames_4kb() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        ByteBuffer payload = newMultiFrameMessage(send, OpCode.BINARY, 4 * MBYTE, 4 * KBYTE);
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new BinaryFrame().setPayload(payload));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        try(LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }

    /**
     * Send 4MB binary message in multiple frames.
     * <p>
     * From Autobahn WebSocket Server Testcase 9.4.9
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testBinary_4mb_Frames_4mb() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        ByteBuffer payload = newMultiFrameMessage(send, OpCode.BINARY, 4 * MBYTE, 4 * MBYTE);
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new BinaryFrame().setPayload(payload));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        try(LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }

    /**
     * Send 4MB binary message in multiple frames.
     * <p>
     * From Autobahn WebSocket Server Testcase 9.4.1
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testBinary_4mb_Frames_64b() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        ByteBuffer payload = newMultiFrameMessage(send, OpCode.BINARY, 4 * MBYTE, 64);
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new BinaryFrame().setPayload(payload));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        try(LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }

    /**
     * Send 4MB binary message in multiple frames.
     * <p>
     * From Autobahn WebSocket Server Testcase 9.4.6
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testBinary_4mb_Frames_64kb() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        ByteBuffer payload = newMultiFrameMessage(send, OpCode.BINARY, 4 * MBYTE, 64 * KBYTE);
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new BinaryFrame().setPayload(payload));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        try(LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }

    /**
     * Echo 4MB binary message (1 frame)
     * <p>
     * From Autobahn WebSocket Server Testcase 9.2.4
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testBinary_4mb_SingleFrame() throws Exception
    {
        byte data[] = new byte[4 * MBYTE];
        Arrays.fill(data,(byte)0x24);
        ByteBuffer buf = ByteBuffer.wrap(data);

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new BinaryFrame().setPayload(buf));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new BinaryFrame().setPayload(DataUtils.copyOf(buf)));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        try(LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }

    /**
     * Echo 64KB binary message (1 frame)
     * <p>
     * From Autobahn WebSocket Server Testcase 9.2.1
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testBinary_64kb_SingleFrame() throws Exception
    {
        byte data[] = new byte[64 * KBYTE];
        Arrays.fill(data,(byte)0x21);

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new BinaryFrame().setPayload(data));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new BinaryFrame().setPayload(DataUtils.copyOf(data)));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        try(LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }

    /**
     * Echo 8MB binary message (1 frame)
     * <p>
     * From Autobahn WebSocket Server Testcase 9.2.5
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testBinary_8mb_SingleFrame() throws Exception
    {
        byte data[] = new byte[8 * MBYTE];
        Arrays.fill(data,(byte)0x25);
        ByteBuffer buf = ByteBuffer.wrap(data);

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new BinaryFrame().setPayload(buf));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new BinaryFrame().setPayload(DataUtils.copyOf(buf)));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        try(LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
}
