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

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.Parser;
import org.eclipse.jetty.websocket.core.WSPolicy;
import org.eclipse.jetty.websocket.core.frames.BinaryFrame;
import org.eclipse.jetty.websocket.core.frames.ContinuationFrame;
import org.eclipse.jetty.websocket.core.frames.DataFrame;
import org.eclipse.jetty.websocket.core.frames.OpCode;
import org.eclipse.jetty.websocket.core.frames.TextFrame;
import org.eclipse.jetty.websocket.core.frames.WSFrame;
import org.eclipse.jetty.websocket.tests.DataUtils;
import org.eclipse.jetty.websocket.tests.SimpleServletServer;
import org.eclipse.jetty.websocket.tests.UnitGenerator;
import org.eclipse.jetty.websocket.tests.server.servlets.EchoServlet;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.rules.TestName;

/**
 * Testing against local websocket server using {@link org.eclipse.jetty.server.LocalConnector}
 */
public abstract class AbstractLocalServerCase
{
    public class ParsedResponse
    {
        public ByteBuffer responseBuffer;
        public HttpTester.Response testerResponse;
        public ByteBuffer remainingBuffer;
    }

    @SuppressWarnings("SpellCheckingInspection")
    protected static final int KBYTE = 1024;
    @SuppressWarnings("SpellCheckingInspection")
    protected static final int MBYTE = KBYTE * KBYTE;
    
    protected static SimpleServletServer server;
    protected final Logger LOG;
    
    @Rule
    public TestName testname = new TestName();
    public UnitGenerator generator = new UnitGenerator(WSPolicy.newClientPolicy());
    
    public AbstractLocalServerCase()
    {
        LOG = Log.getLogger(this.getClass().getName());
    }
    
    @BeforeClass
    public static void startServer() throws Exception
    {
        server = new SimpleServletServer(new EchoServlet());
        server.start();
    }
    
    @AfterClass
    public static void stopServer() throws Exception
    {
        server.stop();
    }
    
    public DataFrame toDataFrame(byte op)
    {
        switch (op)
        {
            case OpCode.BINARY:
                return new BinaryFrame();
            case OpCode.TEXT:
                return new TextFrame();
            case OpCode.CONTINUATION:
                return new ContinuationFrame();
            default:
                throw new IllegalArgumentException("Not a data frame: " + op);
        }
    }
    
    /**
     * Create a new multi-frame data message (TEXT or BINARY with CONTINUATIONS).
     *
     * @param send the list to add the individual frames making up the message
     * @param opcode the opcode (message type: TEXT or BINARY)
     * @param overallSize the overall size of the message
     * @param frameSize the individual frame size to utilize
     * @return the overall message payload (useful for expectation checks)
     */
    public ByteBuffer newMultiFrameMessage(List<WSFrame> send, byte opcode, int overallSize, int frameSize)
    {
        byte msg[] = new byte[overallSize];
        Arrays.fill(msg, (byte) 'M');
        
        byte frag[];
        int remaining = msg.length;
        int offset = 0;
        boolean fin;
        ByteBuffer buf;
        byte op = opcode;
        while (remaining > 0)
        {
            int len = Math.min(remaining, frameSize);
            frag = new byte[len];
            System.arraycopy(msg, offset, frag, 0, len);
            remaining -= len;
            fin = (remaining <= 0);
            buf = ByteBuffer.wrap(frag);
            
            send.add(toDataFrame(op).setPayload(buf).setFin(fin));
            
            offset += len;
            op = OpCode.CONTINUATION;
        }
        
        return ByteBuffer.wrap(msg);
    }
    
    public Parser newClientParser(Parser.Handler parserHandler)
    {
        return new Parser(WSPolicy.newClientPolicy(), new MappedByteBufferPool(), parserHandler);
    }
    
    public ParsedResponse performUpgrade(LocalConnector.LocalEndPoint endPoint, ByteBuffer buf) throws Exception
    {
        endPoint.addInput(buf);
        
        // Get response
        ParsedResponse response = new ParsedResponse();
        response.responseBuffer = endPoint.waitForResponse(false, 1, TimeUnit.SECONDS);
        response.remainingBuffer = DataUtils.copyOf(endPoint.getResponseData());
        response.testerResponse = HttpTester.parseResponse(response.responseBuffer);

        assertThat("Is Switching Protocols", response.testerResponse.getStatus(), is(101));
        assertThat("Is WebSocket Upgrade", response.testerResponse.get("Upgrade"), is("WebSocket"));
        return response;
    }
}
