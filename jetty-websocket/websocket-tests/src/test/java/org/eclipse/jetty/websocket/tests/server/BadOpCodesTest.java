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
import java.util.List;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.Parser;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.frames.PingFrame;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.tests.BadFrame;
import org.eclipse.jetty.websocket.tests.LocalFuzzer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * Test various bad / forbidden opcodes (per spec)
 */
@RunWith(Parameterized.class)
public class BadOpCodesTest extends AbstractLocalServerCase
{
    @Parameterized.Parameters(name = "opcode={0}")
    public static List<Object[]> data()
    {
        List<Object[]> data = new ArrayList<>();
        data.add(new Object[]{3}); // From Autobahn WebSocket Server Testcase 4.1.1
        data.add(new Object[]{4}); // From Autobahn WebSocket Server Testcase 4.1.2
        data.add(new Object[]{5}); // From Autobahn WebSocket Server Testcase 4.1.3
        data.add(new Object[]{6}); // From Autobahn WebSocket Server Testcase 4.1.4
        data.add(new Object[]{7}); // From Autobahn WebSocket Server Testcase 4.1.5
        data.add(new Object[]{11}); // From Autobahn WebSocket Server Testcase 4.2.1
        data.add(new Object[]{12}); // From Autobahn WebSocket Server Testcase 4.2.2
        data.add(new Object[]{13}); // From Autobahn WebSocket Server Testcase 4.2.3
        data.add(new Object[]{14}); // From Autobahn WebSocket Server Testcase 4.2.4
        data.add(new Object[]{15}); // From Autobahn WebSocket Server Testcase 4.2.5
        
        return data;
    }
    
    @Parameterized.Parameter
    public int opcode;
    
    @Test
    public void testBadOpCode() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new BadFrame((byte) opcode)); // intentionally bad
        
        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseInfo(StatusCode.PROTOCOL).asFrame());
    
        try (StacklessLogging ignored = new StacklessLogging(Parser.class);
             LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
    
    @Test
    public void testText_BadOpCode_Ping() throws Exception
    {
        ByteBuffer buf = ByteBuffer.wrap(StringUtil.getUtf8Bytes("bad"));
        
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload("hello"));
        send.add(new BadFrame((byte) opcode).setPayload(buf)); // intentionally bad
        send.add(new PingFrame());
        
        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload("hello")); // echo
        expect.add(new CloseInfo(StatusCode.PROTOCOL).asFrame());
    
        try (StacklessLogging ignored = new StacklessLogging(Parser.class);
             LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
}
