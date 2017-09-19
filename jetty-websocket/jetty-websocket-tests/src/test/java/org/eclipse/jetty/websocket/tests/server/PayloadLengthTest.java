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
import org.eclipse.jetty.websocket.core.frames.BinaryFrame;
import org.eclipse.jetty.websocket.core.frames.CloseFrame;
import org.eclipse.jetty.websocket.core.frames.TextFrame;
import org.eclipse.jetty.websocket.core.frames.WSFrame;
import org.eclipse.jetty.websocket.tests.DataUtils;
import org.eclipse.jetty.websocket.tests.LocalFuzzer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class PayloadLengthTest extends AbstractLocalServerCase
{
    @Parameterized.Parameters(name = "{0} bytes")
    public static List<Object[]> data()
    {
        List<Object[]> cases = new ArrayList<>();
        // Uses small 7-bit payload length format
        cases.add(new Object[]{0}); // From Autobahn WebSocket Server Testcase 1.1.1
        cases.add(new Object[]{1});
        cases.add(new Object[]{125}); // From Autobahn WebSocket Server Testcase 1.1.2
        // Uses medium 2 byte payload length format
        cases.add(new Object[]{126}); // From Autobahn WebSocket Server Testcase 1.1.3
        cases.add(new Object[]{127}); // From Autobahn WebSocket Server Testcase 1.1.4
        cases.add(new Object[]{128}); // From Autobahn WebSocket Server Testcase 1.1.5
        cases.add(new Object[]{65535}); // From Autobahn WebSocket Server Testcase 1.1.6
        // Uses large 8 byte payload length
        cases.add(new Object[]{65536}); // From Autobahn WebSocket Server Testcase 1.1.7
        cases.add(new Object[]{500 * 1024});
        return cases;
    }
    
    @Parameterized.Parameter
    public int size;
    
    @Test
    public void testTextPayloadLength() throws Exception
    {
        byte payload[] = new byte[size];
        Arrays.fill(payload, (byte) 'x');
        ByteBuffer buf = ByteBuffer.wrap(payload);
        
        List<WSFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload(buf));
        send.add(new CloseFrame().setPayload(StatusCode.NORMAL.getCode()));
        
        List<WSFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload(DataUtils.copyOf(buf)));
        expect.add(new CloseFrame().setPayload(StatusCode.NORMAL.getCode()));
    
        try (LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
    
    @Test
    public void testTextPayloadLength_SmallBuffers() throws Exception
    {
        byte payload[] = new byte[size];
        Arrays.fill(payload, (byte) 'x');
        ByteBuffer buf = ByteBuffer.wrap(payload);
        
        List<WSFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload(buf));
        send.add(new CloseFrame().setPayload(StatusCode.NORMAL.getCode()));
        
        List<WSFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload(DataUtils.copyOf(buf)));
        expect.add(new CloseFrame().setPayload(StatusCode.NORMAL.getCode()));
    
        try (LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendSegmented(send, 10);
            session.expect(expect);
        }
    }
    
    @Test
    public void testBinaryPayloadLength() throws Exception
    {
        byte payload[] = new byte[size];
        Arrays.fill(payload, (byte) 0xFE);
        ByteBuffer buf = ByteBuffer.wrap(payload);
        
        List<WSFrame> send = new ArrayList<>();
        send.add(new BinaryFrame().setPayload(buf));
        send.add(new CloseFrame().setPayload(StatusCode.NORMAL.getCode()));
        
        List<WSFrame> expect = new ArrayList<>();
        expect.add(new BinaryFrame().setPayload(DataUtils.copyOf(buf)));
        expect.add(new CloseFrame().setPayload(StatusCode.NORMAL.getCode()));
    
        try (LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
    
    @Test
    public void testBinaryPayloadLength_SmallBuffers() throws Exception
    {
        byte payload[] = new byte[size];
        Arrays.fill(payload, (byte) 0xFE);
        ByteBuffer buf = ByteBuffer.wrap(payload);
        
        List<WSFrame> send = new ArrayList<>();
        send.add(new BinaryFrame().setPayload(buf));
        send.add(new CloseFrame().setPayload(StatusCode.NORMAL.getCode()));
        
        List<WSFrame> expect = new ArrayList<>();
        expect.add(new BinaryFrame().setPayload(DataUtils.copyOf(buf)));
        expect.add(new CloseFrame().setPayload(StatusCode.NORMAL.getCode()));
    
        try (LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendSegmented(send, 10);
            session.expect(expect);
        }
    }
}
