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
import java.util.List;
import java.util.stream.Stream;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.frames.PingFrame;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.tests.BadFrame;
import org.eclipse.jetty.websocket.tests.LocalFuzzer;
import org.eclipse.jetty.websocket.tests.servlets.EchoSocket;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Test various bad / forbidden opcodes (per spec)
 */
public class BadOpCodesTest extends AbstractLocalServerCase
{
    public static Stream<Arguments> data()
    {
        List<Arguments> data = new ArrayList<>();
        data.add(Arguments.of(3)); // From Autobahn WebSocket Server Testcase 4.1.1
        data.add(Arguments.of(4)); // From Autobahn WebSocket Server Testcase 4.1.2
        data.add(Arguments.of(5)); // From Autobahn WebSocket Server Testcase 4.1.3
        data.add(Arguments.of(6)); // From Autobahn WebSocket Server Testcase 4.1.4
        data.add(Arguments.of(7)); // From Autobahn WebSocket Server Testcase 4.1.5
        data.add(Arguments.of(11)); // From Autobahn WebSocket Server Testcase 4.2.1
        data.add(Arguments.of(12)); // From Autobahn WebSocket Server Testcase 4.2.2
        data.add(Arguments.of(13)); // From Autobahn WebSocket Server Testcase 4.2.3
        data.add(Arguments.of(14)); // From Autobahn WebSocket Server Testcase 4.2.4
        data.add(Arguments.of(15)); // From Autobahn WebSocket Server Testcase 4.2.5
        
        return data.stream();
    }
    
    @ParameterizedTest
    @MethodSource("data")
    public void testBadOpCode(int opcode) throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new BadFrame((byte) opcode)); // intentionally bad
        
        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseInfo(StatusCode.PROTOCOL).asFrame());
    
        try (StacklessLogging ignored = new StacklessLogging(EchoSocket.class);
             LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testText_BadOpCode_Ping(int opcode) throws Exception
    {
        ByteBuffer buf = ByteBuffer.wrap(StringUtil.getUtf8Bytes("bad"));
        
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload("hello"));
        send.add(new BadFrame((byte) opcode).setPayload(buf)); // intentionally bad
        send.add(new PingFrame());
        
        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload("hello")); // echo
        expect.add(new CloseInfo(StatusCode.PROTOCOL).asFrame());
    
        try (StacklessLogging ignored = new StacklessLogging(EchoSocket.class);
             LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
}
