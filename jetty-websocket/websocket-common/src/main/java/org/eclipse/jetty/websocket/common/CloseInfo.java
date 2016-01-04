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

package org.eclipse.jetty.websocket.common;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.util.Utf8Appendable.NotUtf8Exception;
import org.eclipse.jetty.websocket.api.BadPayloadException;
import org.eclipse.jetty.websocket.api.CloseStatus;
import org.eclipse.jetty.websocket.api.ProtocolException;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.frames.CloseFrame;

public class CloseInfo
{
    private int statusCode;
    private byte[] reasonBytes;

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
                if ((statusCode < StatusCode.NORMAL) || (statusCode == StatusCode.UNDEFINED) || (statusCode == StatusCode.NO_CLOSE)
                        || (statusCode == StatusCode.NO_CODE) || ((statusCode > 1011) && (statusCode <= 2999)) || (statusCode >= 5000))
                {
                    throw new ProtocolException("Invalid close code: " + statusCode);
                }
            }

            if (data.remaining() > 0)
            {
                // Reason (trimmed to max reason size)
                int len = Math.min(data.remaining(), CloseStatus.MAX_REASON_PHRASE);
                reasonBytes = new byte[len];
                data.get(reasonBytes,0,len);
                
                // Spec Requirement : throw BadPayloadException on invalid UTF8
                if(validate)
                {
                    try
                    {
                        Utf8StringBuilder utf = new Utf8StringBuilder();
                        // if this throws, we know we have bad UTF8
                        utf.append(reasonBytes,0,reasonBytes.length);
                    }
                    catch (NotUtf8Exception e)
                    {
                        throw new BadPayloadException("Invalid Close Reason",e);
                    }
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
        if (reason != null)
        {
            byte[] utf8Bytes = reason.getBytes(StandardCharsets.UTF_8);
            if (utf8Bytes.length > CloseStatus.MAX_REASON_PHRASE)
            {
                this.reasonBytes = new byte[CloseStatus.MAX_REASON_PHRASE];
                System.arraycopy(utf8Bytes,0,this.reasonBytes,0,CloseStatus.MAX_REASON_PHRASE);
            }
            else
            {
                this.reasonBytes = utf8Bytes;
            }
        }
    }

    private ByteBuffer asByteBuffer()
    {
        if ((statusCode == StatusCode.NO_CLOSE) || (statusCode == StatusCode.NO_CODE) || (statusCode == (-1)))
        {
            // codes that are not allowed to be used in endpoint.
            return null;
        }

        int len = 2; // status code
        boolean hasReason = (this.reasonBytes != null) && (this.reasonBytes.length > 0);
        if (hasReason)
        {
            len += this.reasonBytes.length;
        }

        ByteBuffer buf = BufferUtil.allocate(len);
        BufferUtil.flipToFill(buf);
        buf.put((byte)((statusCode >>> 8) & 0xFF));
        buf.put((byte)((statusCode >>> 0) & 0xFF));

        if (hasReason)
        {
            buf.put(this.reasonBytes,0,this.reasonBytes.length);
        }
        BufferUtil.flipToFlush(buf,0);

        return buf;
    }

    public CloseFrame asFrame()
    {
        CloseFrame frame = new CloseFrame();
        frame.setFin(true);
        if ((statusCode >= 1000) && (statusCode != StatusCode.NO_CLOSE) && (statusCode != StatusCode.NO_CODE))
        {
            if (statusCode == StatusCode.FAILED_TLS_HANDSHAKE)
            {
                throw new ProtocolException("Close Frame with status code " + statusCode + " not allowed (per RFC6455)");
            }
            frame.setPayload(asByteBuffer());
        }
        return frame;
    }

    public String getReason()
    {
        if (this.reasonBytes == null)
        {
            return null;
        }
        return new String(this.reasonBytes,StandardCharsets.UTF_8);
    }

    public int getStatusCode()
    {
        return statusCode;
    }

    public boolean isHarsh()
    {
        return !((statusCode == StatusCode.NORMAL) || (statusCode == StatusCode.NO_CODE));
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
