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

package org.eclipse.jetty.websocket.core.frames;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.ProtocolException;
import org.eclipse.jetty.websocket.core.StatusCode;

public class CloseFrame extends ControlFrame
{
    public CloseFrame()
    {
        super(OpCode.CLOSE);
    }
    
    @Override
    public Type getType()
    {
        return Type.CLOSE;
    }

    public WebSocketFrame setPayload(int statusCode, String reason)
    {
        // No code? don't generate a payload.
        if(statusCode > 0)
        {
            setPayload(asPayloadBuffer(statusCode, reason));
        }

        return this;
    }

    public static ByteBuffer asPayloadBuffer(int statusCode, String reason)
    {
        assertValidStatusCode(statusCode);

        int len = 2; // status code

        byte reasonBytes[] = null;

        if (reason != null)
        {
            byte[] utf8Bytes = reason.getBytes(StandardCharsets.UTF_8);
            if (utf8Bytes.length > CloseStatus.MAX_REASON_PHRASE)
            {
                reasonBytes = new byte[CloseStatus.MAX_REASON_PHRASE];
                System.arraycopy(utf8Bytes, 0, reasonBytes, 0, CloseStatus.MAX_REASON_PHRASE);
            }
            else
            {
                reasonBytes = utf8Bytes;
            }

            if ((reasonBytes != null) && (reasonBytes.length > 0))
            {
                len += reasonBytes.length;
            }
        }

        ByteBuffer buf = BufferUtil.allocate(len);
        BufferUtil.flipToFill(buf);
        buf.put((byte) ((statusCode >>> 8) & 0xFF));
        buf.put((byte) ((statusCode >>> 0) & 0xFF));

        if ((reasonBytes != null) && (reasonBytes.length > 0))
        {
            buf.put(reasonBytes, 0, reasonBytes.length);
        }
        BufferUtil.flipToFlush(buf, 0);

        return buf;
    }

    public static void assertValidStatusCode(int statusCode)
    {
        // Status Codes outside of RFC6455 defined scope
        if ((statusCode <= 999) || (statusCode >= 5000))
        {
            throw new ProtocolException("Out of range close status code: " + statusCode);
        }

        // Status Codes not allowed to exist in a Close frame (per RFC6455)
        if ((statusCode == StatusCode.NO_CLOSE) || (statusCode == StatusCode.NO_CODE) || (statusCode == StatusCode.FAILED_TLS_HANDSHAKE))
        {
            throw new ProtocolException("Frame forbidden close status code: " + statusCode);
        }

        // Status Code is in defined "reserved space" and is declared (all others are invalid)
        if ((statusCode >= 1000) && (statusCode <= 2999) && !StatusCode.isTransmittable(statusCode))
        {
            throw new ProtocolException("RFC6455 and IANA Undefined close status code: " + statusCode);
        }
    }
}
