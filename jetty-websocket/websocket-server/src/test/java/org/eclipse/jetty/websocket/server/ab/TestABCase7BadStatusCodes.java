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
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.frames.CloseFrame;
import org.eclipse.jetty.websocket.common.test.Fuzzer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Test Bad Close Status Codes
 */
public class TestABCase7BadStatusCodes extends AbstractABCase
{
    private static final Logger LOG = Log.getLogger(TestABCase7GoodStatusCodes.class);

    public static Stream<Arguments> data()
    {
        // The various Good UTF8 sequences as a String (hex form)
        List<Object[]> data = new ArrayList<>();

        data.add(new Object[]{"7.9.1", 0});
        data.add(new Object[]{"7.9.2", 999});
        data.add(new Object[]{"7.9.3", 1004}); // RFC6455/UNDEFINED
        data.add(new Object[]{"7.9.4", 1005}); // RFC6455/Cannot Be Transmitted
        data.add(new Object[]{"7.9.5", 1006}); // RFC6455/Cannot Be Transmitted
        // data.add(new Object[] { "7.9.6", 1012 }); - IANA Defined
        // data.add(new Object[] { "7.9.7", 1013 }); - IANA Defined
        // data.add(new Object[] { "7.9.8", 1014 }); - IANA Defined
        data.add(new Object[]{"7.9.9", 1015}); // RFC6455/Cannot Be Transmitted
        data.add(new Object[]{"7.9.10", 1016});
        data.add(new Object[]{"7.9.11", 1100});
        data.add(new Object[]{"7.9.12", 2000});
        data.add(new Object[]{"7.9.13", 2999});
        // -- close status codes, with undefined events in spec 
        data.add(new Object[]{"7.13.1", 5000});
        data.add(new Object[]{"7.13.2", 65536});

        return data.stream().map(Arguments::of);
    }

    /**
     * just the close code, no reason
     *
     * @throws Exception on test failure
     */
    @ParameterizedTest
    @MethodSource("data")
    public void testBadStatusCode(String testId, int statusCode) throws Exception
    {
        ByteBuffer payload = ByteBuffer.allocate(256);
        BufferUtil.clearToFill(payload);
        payload.putChar((char)statusCode);
        BufferUtil.flipToFlush(payload, 0);

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new CloseFrame().setPayload(payload.slice()));

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseInfo(StatusCode.PROTOCOL).asFrame());

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
     * the bad close code, with reason
     *
     * @throws Exception on test failure
     */
    @ParameterizedTest
    @MethodSource("data")
    public void testBadStatusCodeWithReason(String testId, int statusCode) throws Exception
    {
        ByteBuffer payload = ByteBuffer.allocate(256);
        BufferUtil.clearToFill(payload);
        payload.putChar((char)statusCode);
        payload.put(StringUtil.getBytes("Reason"));
        BufferUtil.flipToFlush(payload, 0);

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new CloseFrame().setPayload(payload.slice()));

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseInfo(StatusCode.PROTOCOL).asFrame());

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
