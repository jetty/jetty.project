//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.common.Parser;
import org.eclipse.jetty.websocket.common.test.IncomingFramesCapture;
import org.eclipse.jetty.websocket.common.test.UnitParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

public class TestABCase4
{
    private WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.CLIENT);

    @Test
    public void testParserControlOpCode11Case421() throws Exception
    {
        ByteBuffer expected = ByteBuffer.allocate(32);

        expected.put(new byte[]{(byte)0x8b, 0x00});

        expected.flip();

        IncomingFramesCapture capture = new IncomingFramesCapture();

        try (StacklessLogging ignore = new StacklessLogging(Parser.class))
        {
            Parser parser = new UnitParser(policy);
            parser.setIncomingFramesHandler(capture);
            assertThrows(ProtocolException.class, () -> parser.parse(expected));
        }
    }

    @Test
    public void testParserControlOpCode12WithPayloadCase422() throws Exception
    {
        ByteBuffer expected = ByteBuffer.allocate(32);

        expected.put(new byte[]{(byte)0x8c, 0x01, 0x00});

        expected.flip();

        IncomingFramesCapture capture = new IncomingFramesCapture();

        try (StacklessLogging ignore = new StacklessLogging(Parser.class))
        {
            Parser parser = new UnitParser(policy);
            parser.setIncomingFramesHandler(capture);
            assertThrows(ProtocolException.class, () -> parser.parse(expected));
        }
    }

    @Test
    public void testParserNonControlOpCode3Case411() throws Exception
    {
        ByteBuffer expected = ByteBuffer.allocate(32);

        expected.put(new byte[]{(byte)0x83, 0x00});

        expected.flip();

        IncomingFramesCapture capture = new IncomingFramesCapture();

        try (StacklessLogging ignore = new StacklessLogging(Parser.class))
        {
            Parser parser = new UnitParser(policy);
            parser.setIncomingFramesHandler(capture);
            assertThrows(ProtocolException.class, () -> parser.parse(expected));
        }
    }

    @Test
    public void testParserNonControlOpCode4WithPayloadCase412() throws Exception
    {
        ByteBuffer expected = ByteBuffer.allocate(32);

        expected.put(new byte[]{(byte)0x84, 0x01, 0x00});

        expected.flip();

        IncomingFramesCapture capture = new IncomingFramesCapture();

        try (StacklessLogging ignore = new StacklessLogging(Parser.class))
        {
            Parser parser = new UnitParser(policy);
            parser.setIncomingFramesHandler(capture);
            assertThrows(ProtocolException.class, () -> parser.parse(expected));
        }
    }
}
