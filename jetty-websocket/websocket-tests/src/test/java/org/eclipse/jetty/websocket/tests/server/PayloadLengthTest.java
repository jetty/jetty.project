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
import java.util.stream.Stream;

import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.frames.BinaryFrame;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.tests.DataUtils;
import org.eclipse.jetty.websocket.tests.LocalFuzzer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class PayloadLengthTest extends AbstractLocalServerCase
{

    public static Stream<Arguments> data()
    {
        List<Arguments> cases = new ArrayList<>();

        // Uses small 7-bit payload length format
        cases.add(Arguments.of(0)); // From Autobahn WebSocket Server Testcase 1.1.1
        cases.add(Arguments.of(1));
        cases.add(Arguments.of(125)); // From Autobahn WebSocket Server Testcase 1.1.2
        // Uses medium 2 byte payload length format
        cases.add(Arguments.of(126)); // From Autobahn WebSocket Server Testcase 1.1.3
        cases.add(Arguments.of(127)); // From Autobahn WebSocket Server Testcase 1.1.4
        cases.add(Arguments.of(128)); // From Autobahn WebSocket Server Testcase 1.1.5
        cases.add(Arguments.of(65535)); // From Autobahn WebSocket Server Testcase 1.1.6
        // Uses large 8 byte payload length
        cases.add(Arguments.of(65536)); // From Autobahn WebSocket Server Testcase 1.1.7
        cases.add(Arguments.of(500 * 1024));
        return cases.stream();
    }
    
    @ParameterizedTest
    @MethodSource("data")
    public void testTextPayloadLength(int size) throws Exception
    {
        byte payload[] = new byte[size];
        Arrays.fill(payload, (byte) 'x');
        ByteBuffer buf = ByteBuffer.wrap(payload);
        
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload(buf));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());
        
        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload(DataUtils.copyOf(buf)));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        try (LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testTextPayloadLength_SmallBuffers(int size) throws Exception
    {
        byte payload[] = new byte[size];
        Arrays.fill(payload, (byte) 'x');
        ByteBuffer buf = ByteBuffer.wrap(payload);
        
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload(buf));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());
        
        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload(DataUtils.copyOf(buf)));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        try (LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendSegmented(send, 10);
            session.expect(expect);
        }
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testBinaryPayloadLength(int size) throws Exception
    {
        byte payload[] = new byte[size];
        Arrays.fill(payload, (byte) 0xFE);
        ByteBuffer buf = ByteBuffer.wrap(payload);
        
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new BinaryFrame().setPayload(buf));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());
        
        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new BinaryFrame().setPayload(DataUtils.copyOf(buf)));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        try (LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testBinaryPayloadLength_SmallBuffers(int size) throws Exception
    {
        byte payload[] = new byte[size];
        Arrays.fill(payload, (byte) 0xFE);
        ByteBuffer buf = ByteBuffer.wrap(payload);
        
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new BinaryFrame().setPayload(buf));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());
        
        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new BinaryFrame().setPayload(DataUtils.copyOf(buf)));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        try (LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendSegmented(send, 10);
            session.expect(expect);
        }
    }
}
