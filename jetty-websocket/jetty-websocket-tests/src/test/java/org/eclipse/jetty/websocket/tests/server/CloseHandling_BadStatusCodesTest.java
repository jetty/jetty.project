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
import java.util.Collection;
import java.util.List;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.core.frames.CloseFrame;
import org.eclipse.jetty.websocket.core.frames.WSFrame;
import org.eclipse.jetty.websocket.tests.LocalFuzzer;
import org.eclipse.jetty.websocket.tests.server.servlets.EchoSocket;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/**
 * Test Bad Close Status Codes
 */
@RunWith(value = Parameterized.class)
public class CloseHandling_BadStatusCodesTest extends AbstractLocalServerCase
{
    @Parameters(name = "{0} {1}")
    public static Collection<Object[]> data()
    {
        // The various Good UTF8 sequences as a String (hex form)
        List<Object[]> data = new ArrayList<>();

        // @formatter:off
        data.add(new Object[] { "7.9.1", 0 });
        data.add(new Object[] { "7.9.2", 999 });
        data.add(new Object[] { "7.9.3", 1004 }); // RFC6455/UNDEFINED
        data.add(new Object[] { "7.9.4", 1005 }); // RFC6455/Cannot Be Transmitted
        data.add(new Object[] { "7.9.5", 1006 }); // RFC6455/Cannot Be Transmitted
        // data.add(new Object[] { "7.9.6", 1012 }); - Now IANA Defined
        // data.add(new Object[] { "7.9.7", 1013 }); - Now IANA Defined
        // data.add(new Object[] { "7.9.8", 1014 }); - Now IANA Defined
        data.add(new Object[] { "7.9.9", 1015 }); // RFC6455/Cannot Be Transmitted
        data.add(new Object[] { "7.9.10", 1016 });
        data.add(new Object[] { "7.9.11", 1100 });
        data.add(new Object[] { "7.9.12", 2000 });
        data.add(new Object[] { "7.9.13", 2999 });
        // -- close status codes, with undefined events in spec 
        data.add(new Object[]{"7.13.1", 5000});
        data.add(new Object[]{"7.13.2", 65536});
        // @formatter:on

        return data;
    }

    private final int statusCode;

    public CloseHandling_BadStatusCodesTest(String testId, int statusCode)
    {
        LOG.debug("Test ID: {}", testId);
        this.statusCode = statusCode;
    }

    /**
     * just the close code, no reason
     *
     * @throws Exception on test failure
     */
    @Test
    public void testBadStatusCode() throws Exception
    {
        ByteBuffer payload = ByteBuffer.allocate(256);
        BufferUtil.clearToFill(payload);
        payload.putChar((char) statusCode);
        BufferUtil.flipToFlush(payload, 0);

        List<WSFrame> send = new ArrayList<>();
        send.add(new CloseFrame().setPayload(payload.slice()));

        List<WSFrame> expect = new ArrayList<>();
        expect.add(new CloseFrame().setPayload(StatusCode.PROTOCOL.getCode()));

        try (StacklessLogging ignore = new StacklessLogging(EchoSocket.class);
             LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }

    /**
     * the bad close code, with reason
     *
     * @throws Exception on test failure
     */
    @Test
    public void testBadStatusCodeWithReason() throws Exception
    {
        ByteBuffer payload = ByteBuffer.allocate(256);
        BufferUtil.clearToFill(payload);
        payload.putChar((char) statusCode);
        payload.put(StringUtil.getBytes("Reason"));
        BufferUtil.flipToFlush(payload, 0);

        List<WSFrame> send = new ArrayList<>();
        send.add(new CloseFrame().setPayload(payload.slice()));

        List<WSFrame> expect = new ArrayList<>();
        expect.add(new CloseFrame().setPayload(StatusCode.PROTOCOL.getCode()));

        try (StacklessLogging ignore = new StacklessLogging(EchoSocket.class);
             LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
}
