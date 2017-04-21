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

package org.eclipse.jetty.websocket.common.ab;

import static org.hamcrest.CoreMatchers.containsString;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.api.ProtocolException;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.common.Parser;
import org.eclipse.jetty.websocket.common.test.ParserCapture;
import org.eclipse.jetty.websocket.common.test.UnitParser;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class TestABCase4
{
    @Rule
    public ExpectedException expectedException = ExpectedException.none();
    
    private WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.CLIENT);

    @Test
    public void testParserControlOpCode11Case4_2_1() throws Exception
    {
        ByteBuffer expected = ByteBuffer.allocate(32);

        expected.put(new byte[] { (byte)0x8b, 0x00 });

        expected.flip();
    
        ParserCapture capture = new ParserCapture();
        Parser parser = new UnitParser(policy,capture);
        
        expectedException.expect(ProtocolException.class);
        expectedException.expectMessage(containsString("Unknown opcode: 11"));
        parser.parse(expected);
    }

    @Test
    public void testParserControlOpCode12WithPayloadCase4_2_2() throws Exception
    {
        ByteBuffer expected = ByteBuffer.allocate(32);

        expected.put(new byte[] { (byte)0x8c, 0x01, 0x00 });

        expected.flip();

        ParserCapture capture = new ParserCapture();
        Parser parser = new UnitParser(policy, capture);
        
        expectedException.expect(ProtocolException.class);
        expectedException.expectMessage(containsString("Unknown opcode: 12"));
        parser.parse(expected);
    }

    @Test
    public void testParserNonControlOpCode3Case4_1_1() throws Exception
    {
        ByteBuffer expected = ByteBuffer.allocate(32);

        expected.put(new byte[] { (byte)0x83, 0x00 });

        expected.flip();
    
        ParserCapture capture = new ParserCapture();
        Parser parser = new UnitParser(policy, capture);
    
        expectedException.expect(ProtocolException.class);
        expectedException.expectMessage(containsString("Unknown opcode: 3"));
        parser.parse(expected);
    }

    @Test
    public void testParserNonControlOpCode4WithPayloadCase4_1_2() throws Exception
    {
        ByteBuffer expected = ByteBuffer.allocate(32);

        expected.put(new byte[] { (byte)0x84, 0x01, 0x00 });

        expected.flip();

        ParserCapture capture = new ParserCapture();
        Parser parser = new UnitParser(policy,capture);
            
        expectedException.expect(ProtocolException.class);
        expectedException.expectMessage(containsString("Unknown opcode: 4"));
        parser.parse(expected);
    }
}
