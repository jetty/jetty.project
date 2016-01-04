//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.websocket.api.ProtocolException;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.common.Parser;
import org.eclipse.jetty.websocket.common.test.IncomingFramesCapture;
import org.eclipse.jetty.websocket.common.test.UnitParser;
import org.junit.Assert;
import org.junit.Test;

public class TestABCase4
{
    private WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.CLIENT);

    @Test
    public void testParserControlOpCode11Case4_2_1() throws Exception
    {
        ByteBuffer expected = ByteBuffer.allocate(32);

        expected.put(new byte[] { (byte)0x8b, 0x00 });

        expected.flip();

        IncomingFramesCapture capture = new IncomingFramesCapture();

        try (StacklessLogging logging = new StacklessLogging(Parser.class))
        {
            Parser parser = new UnitParser(policy);
            parser.setIncomingFramesHandler(capture);
            try
            {
                parser.parse(expected);
            }
            catch (ProtocolException ignore)
            {
                // ignore
            }
        }

        Assert.assertEquals("error on undefined opcode",1,capture.getErrorCount(WebSocketException.class));

        Throwable known = capture.getErrors().poll();

        Assert.assertTrue("undefined option should be in message",known.getMessage().contains("Unknown opcode: 11"));
    }

    @Test
    public void testParserControlOpCode12WithPayloadCase4_2_2() throws Exception
    {
        ByteBuffer expected = ByteBuffer.allocate(32);

        expected.put(new byte[] { (byte)0x8c, 0x01, 0x00 });

        expected.flip();

        IncomingFramesCapture capture = new IncomingFramesCapture();

        try (StacklessLogging logging = new StacklessLogging(Parser.class))
        {
            Parser parser = new UnitParser(policy);
            parser.setIncomingFramesHandler(capture);
            try
            {
                parser.parse(expected);
            }
            catch (ProtocolException ignore)
            {
                // ignore
            }
        }

        Assert.assertEquals("error on undefined opcode",1,capture.getErrorCount(WebSocketException.class));

        Throwable known = capture.getErrors().poll();

        Assert.assertTrue("undefined option should be in message",known.getMessage().contains("Unknown opcode: 12"));
    }

    @Test
    public void testParserNonControlOpCode3Case4_1_1() throws Exception
    {
        ByteBuffer expected = ByteBuffer.allocate(32);

        expected.put(new byte[] { (byte)0x83, 0x00 });

        expected.flip();

        IncomingFramesCapture capture = new IncomingFramesCapture();

        try (StacklessLogging logging = new StacklessLogging(Parser.class))
        {
            Parser parser = new UnitParser(policy);
            parser.setIncomingFramesHandler(capture);
            try
            {
                parser.parse(expected);
            }
            catch (ProtocolException ignore)
            {
                // ignore
            }
        }

        Assert.assertEquals("error on undefined opcode",1,capture.getErrorCount(WebSocketException.class));

        Throwable known = capture.getErrors().poll();

        Assert.assertTrue("undefined option should be in message",known.getMessage().contains("Unknown opcode: 3"));
    }

    @Test
    public void testParserNonControlOpCode4WithPayloadCase4_1_2() throws Exception
    {
        ByteBuffer expected = ByteBuffer.allocate(32);

        expected.put(new byte[] { (byte)0x84, 0x01, 0x00 });

        expected.flip();

        IncomingFramesCapture capture = new IncomingFramesCapture();

        try (StacklessLogging logging = new StacklessLogging(Parser.class))
        {
            Parser parser = new UnitParser(policy);
            parser.setIncomingFramesHandler(capture);
            try
            {
                parser.parse(expected);
            }
            catch (ProtocolException ignore)
            {
                // ignore
            }
        }

        Assert.assertEquals("error on undefined opcode",1,capture.getErrorCount(WebSocketException.class));

        Throwable known = capture.getErrors().poll();

        Assert.assertTrue("undefined option should be in message",known.getMessage().contains("Unknown opcode: 4"));
    }
}
