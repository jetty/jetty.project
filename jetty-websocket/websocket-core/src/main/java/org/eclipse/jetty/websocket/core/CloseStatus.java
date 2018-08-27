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

package org.eclipse.jetty.websocket.core;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Utf8Appendable;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.websocket.core.frames.Frame;

/**
 * Representation of a WebSocket Close (status code &amp; reason)
 */
public class CloseStatus
{
    public static final int NORMAL = 1000;
    public static final int SHUTDOWN = 1001;
    public static final int PROTOCOL = 1002;
    public static final int BAD_DATA = 1003;
    public static final int NO_CODE = 1005;
    public static final int NO_CLOSE = 1006;
    public static final int BAD_PAYLOAD = 1007;
    public static final int POLICY_VIOLATION = 1008;
    public static final int MESSAGE_TOO_LARGE = 1009;
    public static final int EXTENSION_ERROR = 1010;
    public static final int SERVER_ERROR = 1011;
    public static final int FAILED_TLS_HANDSHAKE = 1015;
    
    public static final int MAX_REASON_PHRASE = Frame.MAX_CONTROL_PAYLOAD - 2;

    /**
     * Convenience method for trimming a long reason reason at the maximum reason reason length of 123 UTF-8 bytes (per WebSocket spec).
     *
     * @param reason the proposed reason reason
     * @return the reason reason (trimmed if needed)
     * @deprecated use of this method is strongly discouraged, as it creates too many new objects that are just thrown away to accomplish its goals.
     */
    @Deprecated
    public static String trimMaxReasonLength(String reason)
    {
        if (reason == null)
        {
            return null;
        }

        byte[] reasonBytes = reason.getBytes(StandardCharsets.UTF_8);
        if (reasonBytes.length > MAX_REASON_PHRASE)
        {
            byte[] trimmed = new byte[MAX_REASON_PHRASE];
            System.arraycopy(reasonBytes, 0, trimmed, 0, MAX_REASON_PHRASE);
            return new String(trimmed, StandardCharsets.UTF_8);
        }

        return reason;
    }

    private int code;
    private String reason;

    /**
     * Creates a reason for closing a web socket connection with the no given status code.
     */
    public CloseStatus()
    {
        this(WebSocketConstants.NO_CODE);
    }

    /**
     * Creates a reason for closing a web socket connection with the given status code and no reason phrase.
     *
     * @param statusCode the close code
     */
    public CloseStatus(int statusCode)
    {
        this(statusCode, null);
    }

    /**
     * Creates a reason for closing a web socket connection with the given status code and reason phrase.
     *
     * @param statusCode the close code
     * @param reasonPhrase the reason phrase
     */
    public CloseStatus(int statusCode, String reasonPhrase)
    {
        this.code = statusCode;
        this.reason = reasonPhrase;
        if (reasonPhrase != null && reasonPhrase.length() > MAX_REASON_PHRASE)
        {
            throw new IllegalArgumentException("Phrase exceeds maximum length of " + MAX_REASON_PHRASE);
        }
    }

    public int getCode()
    {
        return code;
    }

    public String getReason()
    {
        return reason;
    }

    public ByteBuffer asPayloadBuffer()
    {
        return asPayloadBuffer(code, reason);
    }



    public static ByteBuffer asPayloadBuffer(int statusCode, String reason)
    {
        if (!isTransmittableStatusCode(statusCode))
            throw new ProtocolException("Invalid close status code: " + statusCode);

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


    /**
     * Test if provided status code can be sent/received on a WebSocket close.
     * <p>
     *     This honors the RFC6455 rules and IANA rules.
     * </p>
     * @param statusCode the statusCode to test
     * @return true if transmittable
     */
    public static boolean isTransmittableStatusCode(int statusCode)
    {
        // Outside of range?
        if ((statusCode <= 999) || (statusCode >= 5000))
        {
            return false;
        }

        // Specifically called out as not-transmittable?
        if ( (statusCode == WebSocketConstants.NO_CLOSE) ||
                (statusCode == WebSocketConstants.NO_CODE) ||
                (statusCode == WebSocketConstants.FAILED_TLS_HANDSHAKE))
        {
            return false;
        }

        // Reserved / not yet allocated
        if ( (statusCode == 1004) || // Reserved in RFC6455
                ( (statusCode >= 1016) && (statusCode <= 2999) ) || // Reserved in RFC6455
                (statusCode >= 5000) ) // RFC6455 Not allowed to be used for any purpose
        {
            return false;
        }

        // All others are allowed
        return true;
    }


    public static CloseStatus toCloseStatus(ByteBuffer payload)
    {
        // RFC-6455 Spec Required Close Frame validation.
        int statusCode = WebSocketConstants.NO_CODE;

        if ((payload == null) || (payload.remaining() == 0))
        {
            return new CloseStatus(statusCode, null); // nothing to do
        }

        ByteBuffer data = payload.slice();
        if (data.remaining() == 1)
        {
            throw new ProtocolException("Invalid 1 byte payload");
        }

        if (data.remaining() > Frame.MAX_CONTROL_PAYLOAD)
        {
            throw new ProtocolException("Invalid control frame length of " + data.remaining() + " bytes");
        }

        if (data.remaining() >= 2)
        {
            // Status Code
            statusCode = 0; // start with 0
            statusCode |= (data.get() & 0xFF) << 8;
            statusCode |= (data.get() & 0xFF);

            if (!isTransmittableStatusCode(statusCode))
            {
                throw new ProtocolException("Invalid Close Code: " + statusCode);
            }

            if (data.remaining() > 0)
            {
                // Reason (trimmed to max reason size)
                int len = Math.min(data.remaining(), CloseStatus.MAX_REASON_PHRASE);
                byte reasonBytes[] = new byte[len];
                data.get(reasonBytes, 0, len);

                // Spec Requirement : throw BadPayloadException on invalid UTF8
                try
                {
                    Utf8StringBuilder utf = new Utf8StringBuilder();
                    // if this throws, we know we have bad UTF8
                    utf.append(reasonBytes, 0, reasonBytes.length);
                    String reason = utf.toString();
                    return new CloseStatus(statusCode, reason);
                }
                catch (Utf8Appendable.NotUtf8Exception e)
                {
                    throw new BadPayloadException("Invalid Close Reason", e);
                }
            }
        }

        return new CloseStatus(statusCode, null);
    }

    public static void verifyPayload(ByteBuffer payload)
    {
        if ((payload == null) || (payload.remaining() == 0))
            return; // nothing to do

        // RFC-6455 Spec Required Close Frame validation.
        ByteBuffer data = payload.slice();
        if (data.remaining() == 1)
            throw new ProtocolException("Invalid 1 byte payload");

        if (data.remaining() > Frame.MAX_CONTROL_PAYLOAD)
            throw new ProtocolException("Invalid control frame length of " + data.remaining() + " bytes");

        if (data.remaining() >= 2)
        {
            // Status Code
            int statusCode;
            statusCode = 0; // start with 0
            statusCode |= (data.get() & 0xFF) << 8;
            statusCode |= (data.get() & 0xFF);

            if (!isTransmittableStatusCode(statusCode))
                throw new ProtocolException("Invalid Close Code: " + statusCode);

            if (data.remaining() > 0)
            {
                // Reason (trimmed to max reason size)
                int len = Math.min(data.remaining(), CloseStatus.MAX_REASON_PHRASE);
                byte reasonBytes[] = new byte[len];
                data.get(reasonBytes, 0, len);

                // Spec Requirement : throw BadPayloadException on invalid UTF8
                try
                {
                    Utf8StringBuilder utf = new Utf8StringBuilder();
                    // if this throws, we know we have bad UTF8
                    utf.append(reasonBytes, 0, reasonBytes.length);
                    utf.toString();
                }
                catch (Utf8Appendable.NotUtf8Exception e)
                {
                    throw new BadPayloadException("Invalid Close Reason", e);
                }
            }
        }
    }


    @Override
    public String toString()
    {
        return String.format("{%03d,%s}",code,reason);
    }
}
