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

package org.eclipse.jetty.websocket.common;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.common.frames.CloseFrame;
import org.junit.jupiter.api.Test;

import static org.eclipse.jetty.websocket.api.StatusCode.FAILED_TLS_HANDSHAKE;
import static org.eclipse.jetty.websocket.api.StatusCode.NORMAL;
import static org.eclipse.jetty.websocket.api.StatusCode.NO_CLOSE;
import static org.eclipse.jetty.websocket.api.StatusCode.NO_CODE;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

public class CloseInfoTest
{
    /**
     * A test where no close is provided
     */
    @Test
    public void testAnonymousClose()
    {
        CloseInfo close = new CloseInfo();
        assertThat("close.code", close.getStatusCode(), is(NO_CODE));
        assertThat("close.reason", close.getReason(), nullValue());

        CloseFrame frame = close.asFrame();
        assertThat("close frame op code", frame.getOpCode(), is(OpCode.CLOSE));
        // should result in no payload
        assertThat("close frame has payload", frame.hasPayload(), is(false));
        assertThat("close frame payload length", frame.getPayloadLength(), is(0));
    }

    /**
     * A test where NO_CODE (1005) is provided
     */
    @Test
    public void testNoCode()
    {
        CloseInfo close = new CloseInfo(NO_CODE);
        assertThat("close.code", close.getStatusCode(), is(NO_CODE));
        assertThat("close.reason", close.getReason(), nullValue());

        CloseFrame frame = close.asFrame();
        assertThat("close frame op code", frame.getOpCode(), is(OpCode.CLOSE));
        // should result in no payload
        assertThat("close frame has payload", frame.hasPayload(), is(false));
        assertThat("close frame payload length", frame.getPayloadLength(), is(0));
    }

    /**
     * A test where NO_CLOSE (1006) is provided
     */
    @Test
    public void testNoClose()
    {
        CloseInfo close = new CloseInfo(NO_CLOSE);
        assertThat("close.code", close.getStatusCode(), is(NO_CLOSE));
        assertThat("close.reason", close.getReason(), nullValue());

        CloseFrame frame = close.asFrame();
        assertThat("close frame op code", frame.getOpCode(), is(OpCode.CLOSE));
        // should result in no payload
        assertThat("close frame has payload", frame.hasPayload(), is(false));
        assertThat("close frame payload length", frame.getPayloadLength(), is(0));
    }

    /**
     * A test of FAILED_TLS_HANDSHAKE (1007)
     */
    @Test
    public void testFailedTlsHandshake()
    {
        CloseInfo close = new CloseInfo(FAILED_TLS_HANDSHAKE);
        assertThat("close.code", close.getStatusCode(), is(FAILED_TLS_HANDSHAKE));
        assertThat("close.reason", close.getReason(), nullValue());

        CloseFrame frame = close.asFrame();
        assertThat("close frame op code", frame.getOpCode(), is(OpCode.CLOSE));
        // should result in no payload
        assertThat("close frame has payload", frame.hasPayload(), is(false));
        assertThat("close frame payload length", frame.getPayloadLength(), is(0));
    }

    /**
     * A test of NORMAL (1000)
     */
    @Test
    public void testNormal()
    {
        CloseInfo close = new CloseInfo(NORMAL);
        assertThat("close.code", close.getStatusCode(), is(NORMAL));
        assertThat("close.reason", close.getReason(), nullValue());

        CloseFrame frame = close.asFrame();
        assertThat("close frame op code", frame.getOpCode(), is(OpCode.CLOSE));
        assertThat("close frame payload length", frame.getPayloadLength(), is(2));
    }

    private ByteBuffer asByteBuffer(int statusCode, String reason)
    {
        int len = 2; // status code length
        byte[] utf = null;
        if (StringUtil.isNotBlank(reason))
        {
            utf = StringUtil.getUtf8Bytes(reason);
            len += utf.length;
        }

        ByteBuffer buf = BufferUtil.allocate(len);
        BufferUtil.flipToFill(buf);
        buf.put((byte)((statusCode >>> 8) & 0xFF));
        buf.put((byte)((statusCode >>> 0) & 0xFF));

        if (utf != null)
        {
            buf.put(utf, 0, utf.length);
        }
        BufferUtil.flipToFlush(buf, 0);

        return buf;
    }

    @Test
    public void testFromFrame()
    {
        ByteBuffer payload = asByteBuffer(NORMAL, null);
        assertThat("payload length", payload.remaining(), is(2));
        CloseFrame frame = new CloseFrame();
        frame.setPayload(payload);

        // create from frame
        CloseInfo close = new CloseInfo(frame);
        assertThat("close.code", close.getStatusCode(), is(NORMAL));
        assertThat("close.reason", close.getReason(), nullValue());

        // and back again
        frame = close.asFrame();
        assertThat("close frame op code", frame.getOpCode(), is(OpCode.CLOSE));
        assertThat("close frame payload length", frame.getPayloadLength(), is(2));
    }
}
