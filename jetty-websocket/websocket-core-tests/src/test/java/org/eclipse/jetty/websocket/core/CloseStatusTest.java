//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.core;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.nullValue;

public class CloseStatusTest
{
    /**
     * A test where no close is provided
     */
    @Test
    public void testAnonymousClose()
    {
        CloseStatus close = new CloseStatus();
        assertThat("close.code", close.getCode(), Matchers.is(CloseStatus.NO_CODE));
        assertThat("close.reason", close.getReason(), nullValue());

        Frame frame = close.toFrame();
        assertThat("close frame op code", frame.getOpCode(), Matchers.is(OpCode.CLOSE));
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
        CloseStatus close = new CloseStatus(CloseStatus.NO_CODE);
        assertThat("close.code", close.getCode(), Matchers.is(CloseStatus.NO_CODE));
        assertThat("close.reason", close.getReason(), nullValue());

        Frame frame = close.toFrame();
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
        CloseStatus close = new CloseStatus(CloseStatus.NO_CLOSE);
        assertThat("close.code", close.getCode(), Matchers.is(CloseStatus.NO_CLOSE));
        assertThat("close.reason", close.getReason(), nullValue());

        Frame frame = close.toFrame();
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
        CloseStatus close = new CloseStatus(CloseStatus.FAILED_TLS_HANDSHAKE);
        assertThat("close.code", close.getCode(), Matchers.is(CloseStatus.FAILED_TLS_HANDSHAKE));
        assertThat("close.reason", close.getReason(), nullValue());

        Frame frame = close.toFrame();
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
        CloseStatus close = new CloseStatus(CloseStatus.NORMAL);
        assertThat("close.code", close.getCode(), Matchers.is(CloseStatus.NORMAL));
        assertThat("close.reason", close.getReason(), nullValue());

        Frame frame = close.toFrame();
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
        ByteBuffer payload = asByteBuffer(CloseStatus.NORMAL, null);
        assertThat("payload length", payload.remaining(), is(2));
        Frame frame = new Frame(OpCode.CLOSE);
        frame.setPayload(payload);

        // create from frame
        CloseStatus close = new CloseStatus(frame.getPayload());
        assertThat("close.code", close.getCode(), Matchers.is(CloseStatus.NORMAL));
        assertThat("close.reason", close.getReason(), nullValue());

        // and back again
        frame = close.toFrame();
        assertThat("close frame op code", frame.getOpCode(), is(OpCode.CLOSE));
        assertThat("close frame payload length", frame.getPayloadLength(), is(2));
    }

    @Test
    public void testLongCloseReason()
    {
        int code = CloseStatus.NORMAL;
        String reason = "___The WebSocket Connection Close Reason_ is defined as" +
            "   the UTF-8-encoded data following the status code (Section 7.4)";

        // @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
        String utf4Bytes = "\uD801\uDC00";

        assertThat(reason.getBytes().length, lessThan(CloseStatus.MAX_REASON_PHRASE));
        assertThat((reason + utf4Bytes).getBytes().length, greaterThan(CloseStatus.MAX_REASON_PHRASE));

        ByteBuffer bb = CloseStatus.asPayloadBuffer(code, reason + utf4Bytes);
        assertThat(bb.limit(), lessThanOrEqualTo(Frame.MAX_CONTROL_PAYLOAD));

        byte[] output = Arrays.copyOfRange(bb.array(), 2, bb.limit());
        assertThat(output, equalTo(reason.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void testLongCloseReasonOfLongCharacters()
    {
        int code = CloseStatus.NORMAL;

        String utf4Bytes = "\uD801\uDC00";
        String reason = utf4Bytes;
        for (int i = 5; i-- > 0; )
        {
            reason = reason + reason;
        }

        ByteBuffer bb = CloseStatus.asPayloadBuffer(code, reason);
        assertThat(bb.limit(), lessThanOrEqualTo(Frame.MAX_CONTROL_PAYLOAD));

        CloseStatus cs = new CloseStatus(bb);
        reason = cs.getReason();

        byte[] output = Arrays.copyOfRange(bb.array(), 2, bb.limit());
        assertThat(output, equalTo(reason.getBytes(StandardCharsets.UTF_8)));
    }
}
