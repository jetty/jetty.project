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
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Utf8Appendable;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.websocket.core.frames.Frame;
import org.eclipse.jetty.websocket.core.frames.OpCode;

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
    
    public static final CloseStatus NO_CODE_STATUS = new CloseStatus(NO_CODE);
    public static final CloseStatus NORMAL_STATUS = new CloseStatus(NORMAL);

    private final int code;
    private final String reason;

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
    }

    public CloseStatus(Frame frame)
    {
        this(frame.getPayload());
    }

    public CloseStatus(ByteBuffer payload)
    {
        // RFC-6455 Spec Required Close Frame validation.
        int statusCode = WebSocketConstants.NO_CODE;

        if ((payload == null) || (payload.remaining() == 0))
        {
            this.code = statusCode;
            this.reason = null;
            return;
        }

        ByteBuffer data = payload.slice();
        if (data.remaining() == 1)
        {
            throw new ProtocolException("Invalid CLOSE payload");
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
                throw new ProtocolException("Invalid CLOSE Code: " + statusCode);
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

                    this.code = statusCode;
                    this.reason = reason;
                    return;
                }
                catch (Utf8Appendable.NotUtf8Exception e)
                {
                    throw new BadPayloadException("Invalid CLOSE Reason", e);
                }
            }
        }

        this.code = statusCode;
        this.reason = null;
        return;
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
            // TODO this can cause an exception related to the encoding, catch and throw BadPayloadException
            byte[] utf8Bytes = reason.getBytes(StandardCharsets.UTF_8);
            if (utf8Bytes.length > CloseStatus.MAX_REASON_PHRASE)
            {
                reasonBytes = truncateToFit(utf8Bytes, CloseStatus.MAX_REASON_PHRASE);
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

    private static byte[] truncateToFit(byte[] bytes, int maxBytes)
    {
        if (bytes.length <= maxBytes)
            return bytes;

        ByteBuffer buffer = ByteBuffer.wrap(bytes, 0, maxBytes);
        CharBuffer chars = CharBuffer.allocate(maxBytes);

        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
        decoder.onMalformedInput(CodingErrorAction.IGNORE);
        decoder.decode(buffer, chars, true);
        decoder.flush(chars);

        chars.flip();
        buffer.clear();
        CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder();
        encoder.encode(chars, buffer, true);
        return Arrays.copyOf(buffer.array(), buffer.position());

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
        if ( (statusCode == WebSocketConstants.NO_CODE) ||
             (statusCode == WebSocketConstants.NO_CLOSE) ||
             (statusCode == WebSocketConstants.FAILED_TLS_HANDSHAKE))
        {
            return false;
        }

        // Reserved / not yet allocated
        if ( (statusCode == 1004) || // Reserved in RFC6455 (might be defined in the future)
             ((statusCode >= 1016) && (statusCode <= 2999)) || // Reserved in RFC6455 (for future revisions, and extensions)
             (statusCode >= 5000) ) // RFC6455 Not allowed to be used for any purpose
        {
            return false;
        }

        // All others are allowed
        return true;
    }


    public Frame toFrame()
    {
        return toFrame(code, reason);
    }

    public static Frame toFrame(int closeStatus)
    {
        return toFrame(closeStatus, null);
    }

    public static Frame toFrame(int closeStatus, String reason)
    {
        if (isTransmittableStatusCode(closeStatus))
            return new Frame(OpCode.CLOSE,true,asPayloadBuffer(closeStatus, reason));
        return new Frame(OpCode.CLOSE);
    }



    public static String codeString(int closeStatus)
    {
        switch(closeStatus)
        {
            case NORMAL : return "NORMAL";
            case SHUTDOWN: return "SHUTDOWN";
            case PROTOCOL: return "PROTOCOL";
            case BAD_DATA: return "BAD_DATA";
            case NO_CODE: return "NO_CODE";
            case NO_CLOSE: return "NO_CLOSE";
            case BAD_PAYLOAD: return "BAD_PAYLOAD";
            case POLICY_VIOLATION: return "POLICY_VIOLATION";
            case MESSAGE_TOO_LARGE: return "MESSAGE_TOO_LARGE";
            case EXTENSION_ERROR: return "EXTENSION_ERROR";
            case SERVER_ERROR: return "SERVER_ERROR";
            case FAILED_TLS_HANDSHAKE: return "FAILED_TLS_HANDSHAKE";
            default: return "UNKNOWN";
        }
    }

    @Override
    public String toString()
    {
        return String.format("{%04d=%s,%s}",code,codeString(code),reason);
    }
}
