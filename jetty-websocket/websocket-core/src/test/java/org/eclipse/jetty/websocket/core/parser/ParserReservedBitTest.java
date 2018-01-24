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

package org.eclipse.jetty.websocket.core.parser;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.websocket.core.Generator;
import org.eclipse.jetty.websocket.core.Parser;
import org.eclipse.jetty.websocket.core.ProtocolException;
import org.eclipse.jetty.websocket.core.WebSocketBehavior;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.frames.BinaryFrame;
import org.eclipse.jetty.websocket.core.frames.CloseFrame;
import org.eclipse.jetty.websocket.core.frames.PingFrame;
import org.eclipse.jetty.websocket.core.frames.TextFrame;
import org.eclipse.jetty.websocket.core.frames.WebSocketFrame;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

/**
 * Test various RSV violations
 */
public class ParserReservedBitTest
{
    @Rule
    public ExpectedException expectedException = ExpectedException.none();

    private WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.CLIENT);
    private ByteBufferPool bufferPool = new MappedByteBufferPool();
    private boolean validatingGenerator = false;

    private void expectProtocolException(List<WebSocketFrame> frames)
    {
        ParserCapture parserCapture = new ParserCapture();
        Parser parser = new Parser(policy, bufferPool, parserCapture);

        // generate raw bytebuffer of provided frames
        int size = frames.stream().mapToInt(frame -> frame.getPayloadLength() + Generator.MAX_HEADER_LENGTH).sum();
        ByteBuffer raw = BufferUtil.allocate(size);
        BufferUtil.clearToFill(raw);
        Generator generator = new Generator(policy, bufferPool, validatingGenerator);
        frames.forEach(frame -> generator.generateWholeFrame(frame, raw));
        BufferUtil.flipToFlush(raw, 0);

        // parse buffer
        try (StacklessLogging ignore = new StacklessLogging(Parser.class))
        {
            expectedException.expect(ProtocolException.class);
            expectedException.expectMessage(allOf(containsString("RSV"),containsString("not allowed to be set")));
            parser.parse(raw);
        }
    }

    /**
     * Send small text frame, with RSV1 == true, with no extensions defined.
     * <p>
     * From Autobahn WebSocket Server Testcase 3.1
     * </p>
     *
     * @throws Exception on test failure
     */
    @Test
    public void testCase3_1()
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload("small").setRsv1(true)); // intentionally bad

        expectProtocolException(send);
    }

    /**
     * Send small text frame, send again with RSV2 == true, then ping, with no extensions defined.
     * <p>
     * From Autobahn WebSocket Server Testcase 3.2
     * </p>
     *
     * @throws Exception on test failure
     */
    @Test
    public void testCase3_2()
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload("small"));
        send.add(new TextFrame().setPayload("small").setRsv2(true)); // intentionally bad
        send.add(new PingFrame().setPayload("ping"));

        expectProtocolException(send);
    }

    /**
     * Send small text frame, send again with (RSV1 & RSV2), then ping, with no extensions defined.
     * <p>
     * From Autobahn WebSocket Server Testcase 3.3
     * </p>
     *
     * @throws Exception on test failure
     */
    @Test
    public void testCase3_3()
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload("small"));
        send.add(new TextFrame().setPayload("small").setRsv1(true).setRsv2(true)); // intentionally bad
        send.add(new PingFrame().setPayload("ping"));

        expectProtocolException(send);
    }

    /**
     * Send small text frame, send again with (RSV3), then ping, with no extensions defined.
     * <p>
     * From Autobahn WebSocket Server Testcase 3.4
     * </p>
     *
     * @throws Exception on test failure
     */
    @Test
    public void testCase3_4()
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload("small"));
        send.add(new TextFrame().setPayload("small").setRsv3(true)); // intentionally bad
        send.add(new PingFrame().setPayload("ping"));

        expectProtocolException(send);
    }

    /**
     * Send binary frame with (RSV3 & RSV1), with no extensions defined.
     * <p>
     * From Autobahn WebSocket Server Testcase 3.5
     * </p>
     *
     * @throws Exception on test failure
     */
    @Test
    public void testCase3_5()
    {
        byte payload[] = new byte[8];
        Arrays.fill(payload, (byte) 0xFF);

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new BinaryFrame().setPayload(payload).setRsv3(true).setRsv1(true)); // intentionally bad

        expectProtocolException(send);
    }

    /**
     * Send ping frame with (RSV3 & RSV2), with no extensions defined.
     * <p>
     * From Autobahn WebSocket Server Testcase 3.6
     * </p>
     *
     * @throws Exception on test failure
     */
    @Test
    public void testCase3_6()
    {
        byte payload[] = new byte[8];
        Arrays.fill(payload, (byte) 0xFF);

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new PingFrame().setPayload(payload).setRsv3(true).setRsv2(true)); // intentionally bad

        expectProtocolException(send);
    }

    /**
     * Send close frame with (RSV3 & RSV2 & RSV1), with no extensions defined.
     * <p>
     * From Autobahn WebSocket Server Testcase 3.7
     * </p>
     *
     * @throws Exception on test failure
     */
    @Test
    public void testCase3_7()
    {
        List<WebSocketFrame> send = new ArrayList<>();
        WebSocketFrame frame = new CloseFrame().setPayload(1000);
        frame.setRsv1(true);
        frame.setRsv2(true);
        frame.setRsv3(true);
        send.add(frame); // intentionally bad

        expectProtocolException(send);
    }
}
