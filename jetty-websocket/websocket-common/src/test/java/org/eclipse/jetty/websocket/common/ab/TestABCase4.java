//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.common.IncomingFramesCapture;
import org.eclipse.jetty.websocket.common.LogShush;
import org.eclipse.jetty.websocket.common.Parser;
import org.eclipse.jetty.websocket.common.UnitParser;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class TestABCase4
{
    @BeforeClass
    public static void disableParserStacks()
    {
        LogShush.disableStacks(Parser.class);
    }

    @AfterClass
    public static void enableParserStacks()
    {
        LogShush.enableStacks(Parser.class);
    }

    private WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.CLIENT);

    @Test
    public void testParserControlOpCode11Case4_2_1()
    {
        ByteBuffer expected = ByteBuffer.allocate(32);

        expected.put(new byte[]
                { (byte)0x8b, 0x00 });

        expected.flip();

        Parser parser = new UnitParser(policy);
        IncomingFramesCapture capture = new IncomingFramesCapture();
        parser.setIncomingFramesHandler(capture);
        parser.parse(expected);

        Assert.assertEquals( "error on undefined opcode", 1, capture.getErrorCount(WebSocketException.class)) ;

        Throwable known = capture.getErrors().get(0);

        Assert.assertTrue("undefined option should be in message",known.getMessage().contains("Unknown opcode: 11"));
    }

    @Test
    public void testParserControlOpCode12WithPayloadCase4_2_2()
    {
        ByteBuffer expected = ByteBuffer.allocate(32);

        expected.put(new byte[]
                { (byte)0x8c, 0x01, 0x00 });

        expected.flip();

        Parser parser = new UnitParser(policy);
        IncomingFramesCapture capture = new IncomingFramesCapture();
        parser.setIncomingFramesHandler(capture);
        parser.parse(expected);

        Assert.assertEquals( "error on undefined opcode", 1, capture.getErrorCount(WebSocketException.class)) ;

        Throwable known = capture.getErrors().get(0);

        Assert.assertTrue("undefined option should be in message",known.getMessage().contains("Unknown opcode: 12"));
    }


    @Test
    public void testParserNonControlOpCode3Case4_1_1()
    {
        ByteBuffer expected = ByteBuffer.allocate(32);

        expected.put(new byte[]
                { (byte)0x83, 0x00 });

        expected.flip();

        Parser parser = new UnitParser(policy);
        IncomingFramesCapture capture = new IncomingFramesCapture();
        parser.setIncomingFramesHandler(capture);
        parser.parse(expected);

        Assert.assertEquals( "error on undefined opcode", 1, capture.getErrorCount(WebSocketException.class)) ;

        Throwable known = capture.getErrors().get(0);

        Assert.assertTrue("undefined option should be in message",known.getMessage().contains("Unknown opcode: 3"));
    }

    @Test
    public void testParserNonControlOpCode4WithPayloadCase4_1_2()
    {
        ByteBuffer expected = ByteBuffer.allocate(32);

        expected.put(new byte[]
                { (byte)0x84, 0x01, 0x00 });

        expected.flip();

        Parser parser = new UnitParser(policy);
        IncomingFramesCapture capture = new IncomingFramesCapture();
        parser.setIncomingFramesHandler(capture);
        parser.parse(expected);

        Assert.assertEquals( "error on undefined opcode", 1, capture.getErrorCount(WebSocketException.class)) ;

        Throwable known = capture.getErrors().get(0);

        Assert.assertTrue("undefined option should be in message",known.getMessage().contains("Unknown opcode: 4"));
    }
}
