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

package org.eclipse.jetty.websocket.server.ab;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.frames.CloseFrame;
import org.eclipse.jetty.websocket.common.test.Fuzzer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Test Good Close Status Codes
 */
public class TestABCase7GoodStatusCodes extends AbstractABCase
{
    private static final Logger LOG = Log.getLogger(TestABCase7GoodStatusCodes.class);

    public static Stream<Arguments> statusCodes()
    {
        // The various Good UTF8 sequences as a String (hex form)
        List<Object[]> data = new ArrayList<>();

        data.add(new Object[]{"7.7.1", 1000});
        data.add(new Object[]{"7.7.2", 1001});
        data.add(new Object[]{"7.7.3", 1002});
        data.add(new Object[]{"7.7.4", 1003});
        data.add(new Object[]{"7.7.5", 1007});
        data.add(new Object[]{"7.7.6", 1008});
        data.add(new Object[]{"7.7.7", 1009});
        data.add(new Object[]{"7.7.8", 1010});
        data.add(new Object[]{"7.7.9", 1011});
        data.add(new Object[]{"IANA Assigned", 1012});
        data.add(new Object[]{"IANA Assigned", 1013});
        data.add(new Object[]{"IANA Assigned", 1014});
        data.add(new Object[]{"7.7.10", 3000});
        data.add(new Object[]{"7.7.11", 3999});
        data.add(new Object[]{"7.7.12", 4000});
        data.add(new Object[]{"7.7.13", 4999});

        return data.stream().map(Arguments::of);
    }

    /**
     * just the close code, no reason
     *
     * @throws Exception on test failure
     */
    @ParameterizedTest
    @MethodSource("statusCodes")
    public void testStatusCode(String testId, int statusCode) throws Exception
    {
        ByteBuffer payload = ByteBuffer.allocate(256);
        BufferUtil.clearToFill(payload);
        payload.putChar((char)statusCode);
        BufferUtil.flipToFlush(payload, 0);

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new CloseFrame().setPayload(payload.slice()));

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseFrame().setPayload(clone(payload)));

        try (Fuzzer fuzzer = new Fuzzer(this))
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
            fuzzer.expectNoMoreFrames();
        }
    }

    /**
     * the good close code, with reason
     *
     * @throws Exception on test failure
     */
    @ParameterizedTest
    @MethodSource("statusCodes")
    public void testStatusCodeWithReason(String testId, int statusCode) throws Exception
    {
        ByteBuffer payload = ByteBuffer.allocate(256);
        payload.putChar((char)statusCode);
        payload.put(StringUtil.getBytes("Reason"));
        payload.flip();

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new CloseFrame().setPayload(payload.slice()));

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseFrame().setPayload(clone(payload)));

        try (Fuzzer fuzzer = new Fuzzer(this))
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
            fuzzer.expectNoMoreFrames();
        }
    }
}
