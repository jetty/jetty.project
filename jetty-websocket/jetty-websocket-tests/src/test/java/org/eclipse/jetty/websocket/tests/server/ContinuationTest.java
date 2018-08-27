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

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.toolchain.test.annotation.Slow;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.core.frames.Frame;
import org.eclipse.jetty.websocket.core.frames.OpCode;
import org.eclipse.jetty.websocket.tests.Fuzzer;
import org.eclipse.jetty.websocket.tests.server.servlets.EchoSocket;
import org.junit.Test;

/**
 * Fragmentation Tests
 */
public class ContinuationTest extends AbstractLocalServerCase
{
    /**
     * Send continuation+!fin, then text+fin
     * <p>
     * From Autobahn WebSocket Server Testcase 5.12
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testFragmented_Continuation_MissingFin() throws Exception
    {
        List<Frame> send = new ArrayList<>();
        send.add(new Frame(OpCode.CONTINUATION).setPayload("sorry").setFin(false));
        send.add(new Frame(OpCode.TEXT).setPayload("hello, world"));
        send.add(new Frame(OpCode.CLOSE).setPayload(StatusCode.NORMAL.getCode()));
        
        List<Frame> expect = new ArrayList<>();
        expect.add(new Frame(OpCode.CLOSE).setPayload(StatusCode.PROTOCOL.getCode()));
    
        try (StacklessLogging ignored = new StacklessLogging(EchoSocket.class);
             Fuzzer session = server.newNetworkFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
    
    /**
     * Send continuation+!fin, then text+fin (framewise)
     * <p>
     * From Autobahn WebSocket Server Testcase 5.13
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testFragmented_Continuation_MissingFin_FrameWise() throws Exception
    {
        List<Frame> send = new ArrayList<>();
        send.add(new Frame(OpCode.CONTINUATION).setPayload("sorry").setFin(false));
        send.add(new Frame(OpCode.TEXT).setPayload("hello, world"));
        send.add(new Frame(OpCode.CLOSE).setPayload(StatusCode.NORMAL.getCode()));
        
        List<Frame> expect = new ArrayList<>();
        expect.add(new Frame(OpCode.CLOSE).setPayload(StatusCode.PROTOCOL.getCode()));
    
        try (StacklessLogging ignored = new StacklessLogging(EchoSocket.class);
             Fuzzer session = server.newNetworkFuzzer())
        {
            session.sendFrames(send);
            session.expect(expect);
        }
    }
    
    /**
     * Send continuation+!fin, then text+fin (slowly)
     * <p>
     * From Autobahn WebSocket Server Testcase 5.14
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testFragmented_Continuation_MissingFin_Slowly() throws Exception
    {
        List<Frame> send = new ArrayList<>();
        send.add(new Frame(OpCode.CONTINUATION).setPayload("sorry").setFin(false));
        send.add(new Frame(OpCode.TEXT).setPayload("hello, world"));
        send.add(new Frame(OpCode.CLOSE).setPayload(StatusCode.NORMAL.getCode()));
        
        List<Frame> expect = new ArrayList<>();
        expect.add(new Frame(OpCode.CLOSE).setPayload(StatusCode.PROTOCOL.getCode()));
    
        try (StacklessLogging ignored = new StacklessLogging(EchoSocket.class);
             Fuzzer session = server.newNetworkFuzzer())
        {
            session.sendSegmented(send,1 );
            session.expect(expect);
        }
    }
    
    /**
     * Send continuation+fin, then text+fin
     * <p>
     * From Autobahn WebSocket Server Testcase 5.9
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testFragmented_Continuation_NoPrior() throws Exception
    {
        List<Frame> send = new ArrayList<>();
        send.add(new Frame(OpCode.CONTINUATION).setPayload("sorry").setFin(true));
        send.add(new Frame(OpCode.TEXT).setPayload("hello, world"));
        send.add(new Frame(OpCode.CLOSE).setPayload(StatusCode.NORMAL.getCode()));
        
        List<Frame> expect = new ArrayList<>();
        expect.add(new Frame(OpCode.CLOSE).setPayload(StatusCode.PROTOCOL.getCode()));
    
        try (StacklessLogging ignored = new StacklessLogging(EchoSocket.class);
             Fuzzer session = server.newNetworkFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
    
    /**
     * Send text fragmented properly in 2 frames, then continuation!fin, then text unfragmented.
     * <p>
     * From Autobahn WebSocket Server Testcase 5.15
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testFragmented_Continuation_NoPrior_Alt() throws Exception
    {
        List<Frame> send = new ArrayList<>();
        send.add(new Frame(OpCode.TEXT).setPayload("fragment1").setFin(false));
        send.add(new Frame(OpCode.CONTINUATION).setPayload("fragment2").setFin(true));
        send.add(new Frame(OpCode.CONTINUATION).setPayload("fragment3").setFin(false)); // bad frame
        send.add(new Frame(OpCode.TEXT).setPayload("fragment4").setFin(true));
        send.add(new Frame(OpCode.CLOSE).setPayload(StatusCode.NORMAL.getCode()));
        
        List<Frame> expect = new ArrayList<>();
        expect.add(new Frame(OpCode.TEXT).setPayload("fragment1fragment2"));
        expect.add(new Frame(OpCode.CLOSE).setPayload(StatusCode.PROTOCOL.getCode()));
    
        try (StacklessLogging ignored = new StacklessLogging(EchoSocket.class);
             Fuzzer session = server.newNetworkFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
    
    /**
     * Send continuation+fin, then text+fin (framewise)
     * <p>
     * From Autobahn WebSocket Server Testcase 5.10
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testFragmented_Continuation_NoPrior_FrameWise() throws Exception
    {
        List<Frame> send = new ArrayList<>();
        send.add(new Frame(OpCode.CONTINUATION).setPayload("sorry").setFin(true));
        send.add(new Frame(OpCode.TEXT).setPayload("hello, world"));
        send.add(new Frame(OpCode.CLOSE).setPayload(StatusCode.NORMAL.getCode()));
        
        List<Frame> expect = new ArrayList<>();
        expect.add(new Frame(OpCode.CLOSE).setPayload(StatusCode.PROTOCOL.getCode()));
    
        try (StacklessLogging ignored = new StacklessLogging(EchoSocket.class);
             Fuzzer session = server.newNetworkFuzzer())
        {
            session.sendFrames(send);
            session.expect(expect);
        }
    }
    
    /**
     * (continuation+fin, text!fin, continuation+fin) * 2
     * <p>
     * From Autobahn WebSocket Server Testcase 5.17
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testFragmented_Continuation_NoPrior_NothingToContinue() throws Exception
    {
        List<Frame> send = new ArrayList<>();
        send.add(new Frame(OpCode.CONTINUATION).setPayload("fragment1").setFin(true)); // nothing to continue
        send.add(new Frame(OpCode.TEXT).setPayload("fragment2").setFin(false));
        send.add(new Frame(OpCode.CONTINUATION).setPayload("fragment3").setFin(true));
        send.add(new Frame(OpCode.CONTINUATION).setPayload("fragment4").setFin(true)); // nothing to continue
        send.add(new Frame(OpCode.TEXT).setPayload("fragment5").setFin(false));
        send.add(new Frame(OpCode.CONTINUATION).setPayload("fragment6").setFin(true));
        send.add(new Frame(OpCode.CLOSE).setPayload(StatusCode.NORMAL.getCode()));
        
        List<Frame> expect = new ArrayList<>();
        expect.add(new Frame(OpCode.CLOSE).setPayload(StatusCode.PROTOCOL.getCode()));
    
        try (StacklessLogging ignored = new StacklessLogging(EchoSocket.class);
             Fuzzer session = server.newNetworkFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
    
    /**
     * Send continuation+fin, then text+fin (slowly)
     * <p>
     * From Autobahn WebSocket Server Testcase 5.11
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testFragmented_Continuation_NoPrior_Slowly() throws Exception
    {
        List<Frame> send = new ArrayList<>();
        send.add(new Frame(OpCode.CONTINUATION).setPayload("sorry").setFin(true));
        send.add(new Frame(OpCode.TEXT).setPayload("hello, world"));
        send.add(new Frame(OpCode.CLOSE).setPayload(StatusCode.NORMAL.getCode()));
        
        List<Frame> expect = new ArrayList<>();
        expect.add(new Frame(OpCode.CLOSE).setPayload(StatusCode.PROTOCOL.getCode()));
    
        try (StacklessLogging ignored = new StacklessLogging(EchoSocket.class);
             Fuzzer session = server.newNetworkFuzzer())
        {
            session.sendSegmented(send,1);
            session.expect(expect);
        }
    }
    
    /**
     * (continuation!fin, text!fin, continuation+fin) * 2
     * <p>
     * From Autobahn WebSocket Server Testcase 5.16
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testFragmented_Continuation_NoPrior_Twice() throws Exception
    {
        List<Frame> send = new ArrayList<>();
        send.add(new Frame(OpCode.CONTINUATION).setPayload("fragment1").setFin(false)); // bad frame
        send.add(new Frame(OpCode.TEXT).setPayload("fragment2").setFin(false));
        send.add(new Frame(OpCode.CONTINUATION).setPayload("fragment3").setFin(true));
        send.add(new Frame(OpCode.CONTINUATION).setPayload("fragment4").setFin(false)); // bad frame
        send.add(new Frame(OpCode.TEXT).setPayload("fragment5").setFin(false));
        send.add(new Frame(OpCode.CONTINUATION).setPayload("fragment6").setFin(true));
        send.add(new Frame(OpCode.CLOSE).setPayload(StatusCode.NORMAL.getCode()));
        
        List<Frame> expect = new ArrayList<>();
        expect.add(new Frame(OpCode.CLOSE).setPayload(StatusCode.PROTOCOL.getCode()));
    
        try (StacklessLogging ignored = new StacklessLogging(EchoSocket.class);
             Fuzzer session = server.newNetworkFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
    
    /**
     * text message fragmented in 2 frames, both frames as opcode=TEXT
     * <p>
     * From Autobahn WebSocket Server Testcase 5.18
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testFragmented_MissingContinuation() throws Exception
    {
        List<Frame> send = new ArrayList<>();
        send.add(new Frame(OpCode.TEXT).setPayload("fragment1").setFin(false));
        send.add(new Frame(OpCode.TEXT).setPayload("fragment2").setFin(true)); // bad frame, must be continuation
        send.add(new Frame(OpCode.CLOSE).setPayload(StatusCode.NORMAL.getCode()));
        
        List<Frame> expect = new ArrayList<>();
        expect.add(new Frame(OpCode.CLOSE).setPayload(StatusCode.PROTOCOL.getCode()));
    
        try (StacklessLogging ignored = new StacklessLogging(EchoSocket.class);
             Fuzzer session = server.newNetworkFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
    
    /**
     * Send ping fragmented in 2 packets
     * <p>
     * From Autobahn WebSocket Server Testcase 5.1
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testFragmented_Ping() throws Exception
    {
        List<Frame> send = new ArrayList<>();
        send.add(new Frame(OpCode.PING).setPayload("hello, ").setFin(false));
        send.add(new Frame(OpCode.CONTINUATION).setPayload("world"));
        send.add(new Frame(OpCode.CLOSE).setPayload(StatusCode.NORMAL.getCode()));
        
        List<Frame> expect = new ArrayList<>();
        expect.add(new Frame(OpCode.CLOSE).setPayload(StatusCode.PROTOCOL.getCode()));
    
        try (StacklessLogging ignored = new StacklessLogging(EchoSocket.class);
             Fuzzer session = server.newNetworkFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
    
    /**
     * Send pong fragmented in 2 packets
     * <p>
     * From Autobahn WebSocket Server Testcase 5.2
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testFragmented_Pong() throws Exception
    {
        List<Frame> send = new ArrayList<>();
        send.add(new Frame(OpCode.PONG).setPayload("hello, ").setFin(false));
        send.add(new Frame(OpCode.CONTINUATION).setPayload("world"));
        send.add(new Frame(OpCode.CLOSE).setPayload(StatusCode.NORMAL.getCode()));
        
        List<Frame> expect = new ArrayList<>();
        expect.add(new Frame(OpCode.CLOSE).setPayload(StatusCode.PROTOCOL.getCode()));
    
        try (StacklessLogging ignored = new StacklessLogging(EchoSocket.class);
             Fuzzer session = server.newNetworkFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
    
    /**
     * Send text fragmented in 2 packets
     * <p>
     * From Autobahn WebSocket Server Testcase 5.3
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testFragmented_TextContinuation() throws Exception
    {
        List<Frame> send = new ArrayList<>();
        send.add(new Frame(OpCode.TEXT).setPayload("hello, ").setFin(false));
        send.add(new Frame(OpCode.CONTINUATION).setPayload("world").setFin(true));
        send.add(new Frame(OpCode.CLOSE).setPayload(StatusCode.NORMAL.getCode()));
        
        List<Frame> expect = new ArrayList<>();
        expect.add(new Frame(OpCode.TEXT).setPayload("hello, world"));
        expect.add(new Frame(OpCode.CLOSE).setPayload(StatusCode.NORMAL.getCode()));
    
        try (StacklessLogging ignored = new StacklessLogging(EchoSocket.class);
             Fuzzer session = server.newNetworkFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
    
    /**
     * Send text fragmented in 2 packets (framewise)
     * <p>
     * From Autobahn WebSocket Server Testcase 5.4
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testFragmented_TextContinuation_FrameWise() throws Exception
    {
        List<Frame> send = new ArrayList<>();
        send.add(new Frame(OpCode.TEXT).setPayload("hello, ").setFin(false));
        send.add(new Frame(OpCode.CONTINUATION).setPayload("world").setFin(true));
        send.add(new Frame(OpCode.CLOSE).setPayload(StatusCode.NORMAL.getCode()));
        
        List<Frame> expect = new ArrayList<>();
        expect.add(new Frame(OpCode.TEXT).setPayload("hello, world"));
        expect.add(new Frame(OpCode.CLOSE).setPayload(StatusCode.NORMAL.getCode()));
    
        try (StacklessLogging ignored = new StacklessLogging(EchoSocket.class);
             Fuzzer session = server.newNetworkFuzzer())
        {
            session.sendFrames(send);
            session.expect(expect);
        }
    }
    
    /**
     * send text message fragmented in 5 frames, with 2 pings.
     * <p>
     * From Autobahn WebSocket Server Testcase 5.19 & 5.20
     * </p>
     * @throws Exception on test failure
     */
    @Test
    @Slow
    public void testFragmented_TextContinuation_PingInterleaved() throws Exception
    {
        List<Frame> send = new ArrayList<>();
        send.add(new Frame(OpCode.TEXT).setPayload("f1").setFin(false));
        send.add(new Frame(OpCode.CONTINUATION).setPayload(",f2").setFin(false));
        send.add(new Frame(OpCode.PING).setPayload("pong-1"));
        send.add(new Frame(OpCode.CONTINUATION).setPayload(",f3").setFin(false));
        send.add(new Frame(OpCode.CONTINUATION).setPayload(",f4").setFin(false));
        send.add(new Frame(OpCode.PING).setPayload("pong-2"));
        send.add(new Frame(OpCode.CONTINUATION).setPayload(",f5").setFin(true));
        send.add(new Frame(OpCode.CLOSE).setPayload(StatusCode.NORMAL.getCode()));
        
        List<Frame> expect = new ArrayList<>();
        expect.add(new Frame(OpCode.PONG).setPayload("pong-1"));
        expect.add(new Frame(OpCode.PONG).setPayload("pong-2"));
        expect.add(new Frame(OpCode.TEXT).setPayload("f1,f2,f3,f4,f5"));
        expect.add(new Frame(OpCode.CLOSE).setPayload(StatusCode.NORMAL.getCode()));
    
        try (StacklessLogging ignored = new StacklessLogging(EchoSocket.class);
             Fuzzer session = server.newNetworkFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
    
    /**
     * Send text fragmented in 2 packets (slowly)
     * <p>
     * From Autobahn WebSocket Server Testcase 5.5
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testFragmented_TextContinuation_Slowly() throws Exception
    {
        List<Frame> send = new ArrayList<>();
        send.add(new Frame(OpCode.TEXT).setPayload("hello, ").setFin(false));
        send.add(new Frame(OpCode.CONTINUATION).setPayload("world").setFin(true));
        send.add(new Frame(OpCode.CLOSE).setPayload(StatusCode.NORMAL.getCode()));
        
        List<Frame> expect = new ArrayList<>();
        expect.add(new Frame(OpCode.TEXT).setPayload("hello, world"));
        expect.add(new Frame(OpCode.CLOSE).setPayload(StatusCode.NORMAL.getCode()));
    
        try (StacklessLogging ignored = new StacklessLogging(EchoSocket.class);
             Fuzzer session = server.newNetworkFuzzer())
        {
            session.sendSegmented(send,1);
            session.expect(expect);
        }
    }
    
    /**
     * Send text fragmented in 2 packets, with ping between them
     * <p>
     * From Autobahn WebSocket Server Testcase 5.6
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testFragmented_TextPingContinuation() throws Exception
    {
        List<Frame> send = new ArrayList<>();
        send.add(new Frame(OpCode.TEXT).setPayload("hello, ").setFin(false));
        send.add(new Frame(OpCode.PING).setPayload("ping"));
        send.add(new Frame(OpCode.CONTINUATION).setPayload("world").setFin(true));
        send.add(new Frame(OpCode.CLOSE).setPayload(StatusCode.NORMAL.getCode()));
        
        List<Frame> expect = new ArrayList<>();
        expect.add(new Frame(OpCode.PONG).setPayload("ping"));
        expect.add(new Frame(OpCode.TEXT).setPayload("hello, world"));
        expect.add(new Frame(OpCode.CLOSE).setPayload(StatusCode.NORMAL.getCode()));
    
        try (StacklessLogging ignored = new StacklessLogging(EchoSocket.class);
             Fuzzer session = server.newNetworkFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
    
    /**
     * Send text fragmented in 2 packets, with ping between them (frame wise)
     * <p>
     * From Autobahn WebSocket Server Testcase 5.7
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testFragmented_TextPingContinuation_FrameWise() throws Exception
    {
        List<Frame> send = new ArrayList<>();
        send.add(new Frame(OpCode.TEXT).setPayload("hello, ").setFin(false));
        send.add(new Frame(OpCode.PING).setPayload("ping"));
        send.add(new Frame(OpCode.CONTINUATION).setPayload("world").setFin(true));
        send.add(new Frame(OpCode.CLOSE).setPayload(StatusCode.NORMAL.getCode()));
        
        List<Frame> expect = new ArrayList<>();
        expect.add(new Frame(OpCode.PONG).setPayload("ping"));
        expect.add(new Frame(OpCode.TEXT).setPayload("hello, world"));
        expect.add(new Frame(OpCode.CLOSE).setPayload(StatusCode.NORMAL.getCode()));
    
        try (StacklessLogging ignored = new StacklessLogging(EchoSocket.class);
             Fuzzer session = server.newNetworkFuzzer())
        {
            session.sendFrames(send);
            session.expect(expect);
        }
    }
    
    /**
     * Send text fragmented in 2 packets, with ping between them (slowly)
     * <p>
     * From Autobahn WebSocket Server Testcase 5.8
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testFragmented_TextPingContinuation_Slowly() throws Exception
    {
        List<Frame> send = new ArrayList<>();
        send.add(new Frame(OpCode.TEXT).setPayload("hello, ").setFin(false));
        send.add(new Frame(OpCode.PING).setPayload("ping"));
        send.add(new Frame(OpCode.CONTINUATION).setPayload("world").setFin(true));
        send.add(new Frame(OpCode.CLOSE).setPayload(StatusCode.NORMAL.getCode()));
        
        List<Frame> expect = new ArrayList<>();
        expect.add(new Frame(OpCode.PONG).setPayload("ping"));
        expect.add(new Frame(OpCode.TEXT).setPayload("hello, world"));
        expect.add(new Frame(OpCode.CLOSE).setPayload(StatusCode.NORMAL.getCode()));
    
        try (StacklessLogging ignored = new StacklessLogging(EchoSocket.class);
             Fuzzer session = server.newNetworkFuzzer())
        {
            session.sendSegmented(send,1);
            session.expect(expect);
        }
    }
}
