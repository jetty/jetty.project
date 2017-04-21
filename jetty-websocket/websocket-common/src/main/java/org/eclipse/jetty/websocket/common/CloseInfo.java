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

package org.eclipse.jetty.websocket.common;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Utf8Appendable.NotUtf8Exception;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.websocket.api.BadPayloadException;
import org.eclipse.jetty.websocket.api.CloseStatus;
import org.eclipse.jetty.websocket.api.ProtocolException;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.frames.CloseFrame;

public class CloseInfo
{
    private int statusCode;
    private String reason;

    public CloseInfo()
    {
        this(StatusCode.NO_CODE,null);
    }

    /**
     * Parse the Close Frame payload.
     * 
     * @param payload the raw close frame payload.
     * @param validate true if payload should be validated per WebSocket spec.
     */
    public CloseInfo(ByteBuffer payload, boolean validate)
    {
        this.statusCode = StatusCode.NO_CODE;

        if ((payload == null) || (payload.remaining() == 0))
        {
            return; // nothing to do
        }

        ByteBuffer data = payload.slice();
        if ((data.remaining() == 1) && (validate))
        {
            throw new ProtocolException("Invalid 1 byte payload");
        }

        if (data.remaining() >= 2)
        {
            // Status Code
            statusCode = 0; // start with 0
            statusCode |= (data.get() & 0xFF) << 8;
            statusCode |= (data.get() & 0xFF);

            if (validate)
            {
                assertValidStatusCode(statusCode);
            }

            if (data.remaining() > 0)
            {
                // Reason (trimmed to max reason size)
                int len = Math.min(data.remaining(), CloseStatus.MAX_REASON_PHRASE);
                byte reasonBytes[] = new byte[len];
                data.get(reasonBytes,0,len);
                
                // Spec Requirement : throw BadPayloadException on invalid UTF8
                try
                {
                    Utf8StringBuilder utf = new Utf8StringBuilder();
                    // if this throws, we know we have bad UTF8
                    utf.append(reasonBytes,0,reasonBytes.length);
                    this.reason = utf.toString();
                }
                catch (NotUtf8Exception e)
                {
                    if(validate)
                        throw new BadPayloadException("Invalid Close Reason",e);
                }
            }
        }
    }

    public CloseInfo(Frame frame)
    {
        this(frame.getPayload(),false);
    }

    public CloseInfo(Frame frame, boolean validate)
    {
        this(frame.getPayload(),validate);
    }

    public CloseInfo(int statusCode)
    {
        this(statusCode,null);
    }

    /**
     * Create a CloseInfo, trimming the reason to {@link CloseStatus#MAX_REASON_PHRASE} UTF-8 bytes if needed.
     * 
     * @param statusCode the status code
     * @param reason the raw reason code
     */
    public CloseInfo(int statusCode, String reason)
    {
        this.statusCode = statusCode;
        this.reason = reason;
    }
    
    /**
     * Convert a raw status code and reason into a WebSocket Close frame payload buffer.
     *
     * @param statusCode the status code
     * @param reason the optional reason string
     * @return the payload buffer if valid. null if invalid status code for payload buffer.
     */
    public static ByteBuffer asPayloadBuffer(int statusCode, String reason)
    {
        if ((statusCode == StatusCode.NO_CLOSE) || (statusCode == StatusCode.NO_CODE) || (statusCode == (-1)))
        {
            // codes that are not allowed to be used in endpoint.
            return null;
        }
    
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

    private ByteBuffer asByteBuffer()
    {
        return asPayloadBuffer(statusCode, reason);
    }

    public CloseFrame asFrame()
    {
        CloseFrame frame = new CloseFrame();
        frame.setFin(true);
        // Frame forbidden codes result in no status code (and no reason string)
        if ((statusCode != StatusCode.NO_CLOSE) && (statusCode != StatusCode.NO_CODE) && (statusCode != StatusCode.FAILED_TLS_HANDSHAKE))
        {
            assertValidStatusCode(statusCode);
            frame.setPayload(asByteBuffer());
        }
        return frame;
    }
    
    public String getReason()
    {
        return this.reason;
    }

    public int getStatusCode()
    {
        return statusCode;
    }

    public boolean isAbnormal()
    {
        return (statusCode != StatusCode.NORMAL);
    }

    @Override
    public String toString()
    {
        return String.format("CloseInfo[code=%d,reason=%s]",statusCode,getReason());
    }
}
