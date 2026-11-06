//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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

import java.util.Objects;

import org.eclipse.jetty.util.Retainable;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

import static java.nio.charset.StandardCharsets.UTF_8;

/// A WebSocket frame as defined by [RFC 6455. Sec 5.2](https://tools.ietf.org/html/rfc6455#section-5.2)
/// ```
///    0                   1                   2                   3
///    0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
///   +-+-+-+-+-------+-+-------------+-------------------------------+
///   |F|R|R|R| opcode|M| Payload len |    Extended payload length    |
///   |I|S|S|S|  (4)  |A|     (7)     |             (16/64)           |
///   |N|V|V|V|       |S|             |   (if payload len==126/127)   |
///   | |1|2|3|       |K|             |                               |
///   +-+-+-+-+-------+-+-------------+ - - - - - - - - - - - - - - - +
///   |     Extended payload length continued, if payload len == 127  |
///   + - - - - - - - - - - - - - - - +-------------------------------+
///   |                               |Masking-key, if MASK set to 1  |
///   +-------------------------------+-------------------------------+
///   | Masking-key (continued)       |          Payload Data         |
///   +-------------------------------- - - - - - - - - - - - - - - - +
///   :                     Payload Data continued ...                :
///   + - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - +
///   |                     Payload Data continued ...                |
///   +---------------------------------------------------------------+
/// ```
public class Frame implements Retainable
{
    /// Maximum size of control frames, per RFC 6455
    public static final int MAX_CONTROL_PAYLOAD = 125;

    /// Combined FIN + RSV1 + RSV2 + RSV3 + OpCode byte.
    /// ```
    ///   1000_0000 (0x80) = fin
    ///   0100_0000 (0x40) = rsv1
    ///   0010_0000 (0x20) = rsv2
    ///   0001_0000 (0x10) = rsv3
    ///   0000_1111 (0x0F) = opcode
    /// ```
    private final byte finRsvOp;
    private final byte[] mask;
    private final RetainableByteBuffer payload;

    public Frame(byte opCode)
    {
        this(opCode, RetainableByteBuffer.empty());
    }

    public Frame(byte opCode, String payload)
    {
        this(opCode, true, payload);
    }

    public Frame(byte opCode, RetainableByteBuffer payload)
    {
        this(opCode, true, payload);
    }

    public Frame(byte opCode, boolean fin, String payload)
    {
        RetainableByteBuffer buffer = RetainableByteBuffer.wrap(payload, UTF_8);
        this(opCode, fin, buffer);
        buffer.release();
    }

    public Frame(byte opCode, boolean fin, RetainableByteBuffer payload)
    {
        this(opCode, fin, false, payload);
    }

    public Frame(byte opCode, boolean fin, boolean rsv1, RetainableByteBuffer payload)
    {
        byte finRsvOp = (byte)((fin ? 0x80 : 0x00) | (rsv1 ? 0x40 : 0x00) | (opCode & 0x0F));
        this(finRsvOp, null, payload);
    }

    /// @param finRsvOp the combined FIN, RSV1, RSV2, RSV3 and OpCode bits
    /// @param mask the masking key, or `null` if the frame is not masked
    /// @param payload the payload of the frame, retained by this frame
    protected Frame(byte finRsvOp, byte[] mask, RetainableByteBuffer payload)
    {
        this.finRsvOp = finRsvOp;
        this.mask = mask;
        this.payload = Objects.requireNonNull(payload);
        retain();
    }

    public boolean isControlFrame()
    {
        return OpCode.isControlFrame(getOpCode());
    }

    public boolean isDataFrame()
    {
        return OpCode.isDataFrame(getOpCode());
    }

    public byte[] getMask()
    {
        return mask;
    }

    public byte getOpCode()
    {
        return OpCode.getOpCode(finRsvOp);
    }

    public RetainableByteBuffer acquire()
    {
        retain();
        return payload;
    }

    /// Returns the payload of the frame as a UTF-8 string.
    ///
    /// The payload is not consumed by this call.
    ///
    /// Should only be used in testing, because it does not validate the
    /// UTF-8 and a non-FIN frame can contain partial UTF-8 characters.
    ///
    /// @return the payload as a UTF-8 string.
    public String getPayloadAsUTF8()
    {
        return payload.getString(payload.readPosition(), UTF_8);
    }

    public long getPayloadLength()
    {
        return payload.remaining();
    }

    public boolean hasPayload()
    {
        return payload.hasRemaining();
    }

    public boolean isFin()
    {
        return (byte)(finRsvOp & 0x80) != 0;
    }

    public boolean isMasked()
    {
        return mask != null;
    }

    public boolean isRsv1()
    {
        return (byte)(finRsvOp & 0x40) != 0;
    }

    public boolean isRsv2()
    {
        return (byte)(finRsvOp & 0x20) != 0;
    }

    public boolean isRsv3()
    {
        return (byte)(finRsvOp & 0x10) != 0;
    }

    public Frame withMask(byte[] mask)
    {
        return new Frame(finRsvOp, mask, payload);
    }

    @Override
    public boolean isRetained()
    {
        return payload.isRetained();
    }

    @Override
    public void retain()
    {
        payload.retain();
    }

    @Override
    public boolean release()
    {
        return payload.release();
    }

    @Override
    public String toString()
    {
        StringBuilder b = new StringBuilder();
        b.append(OpCode.name(OpCode.getOpCode(finRsvOp)));
        b.append('@');
        b.append(Integer.toHexString(super.hashCode()));
        b.append('[');
        b.append("len=").append(getPayloadLength());
        b.append(",fin=").append((finRsvOp & 0x80) != 0);
        b.append(",rsv=");
        b.append(((finRsvOp & 0x40) != 0) ? '1' : '0');
        b.append(((finRsvOp & 0x20) != 0) ? '1' : '0');
        b.append(((finRsvOp & 0x10) != 0) ? '1' : '0');
        b.append(",m=").append(mask == null ? "null" : StringUtil.toHexString(mask));
        b.append(']');
        b.append(payload);
        return b.toString();
    }
}
