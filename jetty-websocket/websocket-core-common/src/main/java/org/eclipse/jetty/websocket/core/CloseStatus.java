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
import org.eclipse.jetty.util.Utf8Appendable;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.websocket.core.exception.BadPayloadException;
import org.eclipse.jetty.websocket.core.exception.ProtocolException;
import org.eclipse.jetty.websocket.core.internal.NullAppendable;

/**
 * Representation of a WebSocket Close (status code &amp; reason)
 */
public class CloseStatus
{
    public static final int NORMAL = 1000;
    public static final int SHUTDOWN = 1001;
    public static final int PROTOCOL = 1002;
    public static final int BAD_DATA = 1003;
    public static final int RESERVED = 1004;
    public static final int NO_CODE = 1005;
    public static final int NO_CLOSE = 1006;
    public static final int BAD_PAYLOAD = 1007;
    public static final int POLICY_VIOLATION = 1008;
    public static final int MESSAGE_TOO_LARGE = 1009;
    public static final int EXTENSION_ERROR = 1010;
    public static final int SERVER_ERROR = 1011;
    public static final int SERVICE_RESTART = 1012;
    public static final int TRY_AGAIN_LATER = 1013;
    public static final int BAD_GATEWAY = 1014;
    public static final int FAILED_TLS_HANDSHAKE = 1015;

    public static final CloseStatus NO_CODE_STATUS = new CloseStatus(NO_CODE);
    public static final CloseStatus NO_CLOSE_STATUS = new CloseStatus(NO_CLOSE);
    public static final CloseStatus NORMAL_STATUS = new CloseStatus(NORMAL);

    static final int MAX_REASON_PHRASE = Frame.MAX_CONTROL_PAYLOAD - 2;

    private final int code;
    private final String reason;
    private final Throwable cause;

    /**
     * Creates a reason for closing a web socket connection with the no given status code.
     */
    public CloseStatus()
    {
        this(NO_CODE, null, null);
    }

    /**
     * Creates a reason for closing a web socket connection with the given status code and no reason phrase.
     *
     * @param statusCode the close code
     */
    public CloseStatus(int statusCode)
    {
        this(statusCode, null, null);
    }

    /**
     * Creates a reason for closing a web socket connection with the given status code and reason phrase.
     *
     * @param statusCode the close code
     * @param reasonPhrase the reason phrase
     */
    public CloseStatus(int statusCode, String reasonPhrase)
    {
        this(statusCode, reasonPhrase, null);
    }

    /**
     * Creates a reason for closing a web socket connection with the given status code and reason phrase.
     *
     * @param statusCode the close code
     * @param cause the error which caused the close
     */
    public CloseStatus(int statusCode, Throwable cause)
    {
        this(statusCode, cause.getMessage(), cause);
    }

    /**
     * Creates a reason for closing a web socket connection with the given status code and reason phrase.
     *
     * @param statusCode the close code
     * @param reasonPhrase the reason phrase
     * @param cause the error which caused the close
     */
    public CloseStatus(int statusCode, String reasonPhrase, Throwable cause)
    {
        this.code = statusCode;
        this.cause = cause;

        if (reasonPhrase != null)
        {
            byte[] reasonBytes = truncateToFit(reasonPhrase.getBytes(StandardCharsets.UTF_8), CloseStatus.MAX_REASON_PHRASE);
            this.reason = new String(reasonBytes, StandardCharsets.UTF_8);
        }
        else
        {
            this.reason = null;
        }
    }

    public CloseStatus(Frame frame)
    {
        this(frame.getPayload());
    }

    public CloseStatus(ByteBuffer payload)
    {
        // RFC-6455 Spec Required Close Frame validation.
        this.cause = null;
        int statusCode = NO_CODE;

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
                byte[] reasonBytes = new byte[len];
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
    }

    public static CloseStatus getCloseStatus(Frame frame)
    {
        if (frame instanceof CloseStatus.Supplier)
            return ((CloseStatus.Supplier)frame).getCloseStatus();
        if (frame.getOpCode() == OpCode.CLOSE)
            return new CloseStatus(frame);
        throw new IllegalArgumentException("not a close frame");
    }

    public static boolean isOrdinary(int closeCode)
    {
        return (closeCode == NORMAL || closeCode == NO_CODE || closeCode >= 3000);
    }

    public boolean isAbnormal()
    {
        return !isOrdinary(code);
    }

    public Throwable getCause()
    {
        return cause;
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

        byte[] reasonBytes = null;

        if (reason != null)
        {
            byte[] utf8Bytes = reason.getBytes(StandardCharsets.UTF_8);
            reasonBytes = truncateToFit(utf8Bytes, CloseStatus.MAX_REASON_PHRASE);
            if (reasonBytes.length > 0)
                len += reasonBytes.length;
        }

        ByteBuffer buf = BufferUtil.allocate(len);
        BufferUtil.flipToFill(buf);
        buf.put((byte)((statusCode >>> 8) & 0xFF));
        buf.put((byte)(statusCode & 0xFF));

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

        int lastIndex = -1;
        NullAppendable a = new NullAppendable();
        for (int i = 0; i < maxBytes; i++)
        {
            a.append(bytes[i]);
            if (a.isUtf8SequenceComplete())
                lastIndex = i;
        }

        return Arrays.copyOf(bytes, lastIndex + 1);
    }

    /**
     * Test if provided status code can be sent/received on a WebSocket close.
     * <p>
     * This honors the RFC6455 rules and IANA rules.
     * </p>
     *
     * @param statusCode the statusCode to test
     * @return true if transmittable
     */
    public static boolean isTransmittableStatusCode(int statusCode)
    {
        // Transmittable status codes pre-defined by RFC6455 and IANA.
        if ((statusCode >= 1000 && statusCode <= 1003) || (statusCode >= 1007 && statusCode <= 1014))
            return true;

        // Codes 3000-3999 reserved for libraries, frameworks, and applications.
        // Codes 4000-4999 reserved for private use.
        return statusCode >= 3000 && statusCode < 5000;
    }

    public Frame toFrame()
    {
        if (isTransmittableStatusCode(code))
            return new CloseFrame(OpCode.CLOSE, true, asPayloadBuffer(code, reason));
        return new CloseFrame(OpCode.CLOSE);
    }

    public static Frame toFrame(int closeStatus)
    {
        return new CloseStatus(closeStatus).toFrame();
    }

    public static Frame toFrame(int closeStatus, String reason)
    {
        return new CloseStatus(closeStatus, reason).toFrame();
    }

    public static String codeString(int closeStatus)
    {
        switch (closeStatus)
        {
            case NORMAL:
                return "NORMAL";
            case SHUTDOWN:
                return "SHUTDOWN";
            case PROTOCOL:
                return "PROTOCOL";
            case BAD_DATA:
                return "BAD_DATA";
            case RESERVED:
                return "RESERVED";
            case NO_CODE:
                return "NO_CODE";
            case NO_CLOSE:
                return "NO_CLOSE";
            case BAD_PAYLOAD:
                return "BAD_PAYLOAD";
            case POLICY_VIOLATION:
                return "POLICY_VIOLATION";
            case MESSAGE_TOO_LARGE:
                return "MESSAGE_TOO_LARGE";
            case EXTENSION_ERROR:
                return "EXTENSION_ERROR";
            case SERVER_ERROR:
                return "SERVER_ERROR";
            case SERVICE_RESTART:
                return "SERVICE_RESTART";
            case TRY_AGAIN_LATER:
                return "TRY_AGAIN_LATER";
            case BAD_GATEWAY:
                return "BAD_GATEWAY";
            case FAILED_TLS_HANDSHAKE:
                return "FAILED_TLS_HANDSHAKE";
            default:
                return "UNKNOWN";
        }
    }

    public boolean isNormal()
    {
        return code == NORMAL;
    }

    @Override
    public String toString()
    {
        return String.format("{%04d=%s,%s}", code, codeString(code), reason);
    }

    public interface Supplier
    {
        CloseStatus getCloseStatus();
    }

    class CloseFrame extends Frame implements CloseStatus.Supplier
    {
        public CloseFrame(byte opcode)
        {
            super(opcode);
        }

        public CloseFrame(byte opCode, boolean fin, ByteBuffer payload)
        {
            super(opCode, fin, payload);
        }

        @Override
        public CloseStatus getCloseStatus()
        {
            return CloseStatus.this;
        }

        @Override
        public String toString()
        {
            return super.toString() + ":" + CloseStatus.this.toString();
        }
    }
}
