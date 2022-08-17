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
import java.util.Arrays;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;

/**
 * A Base Frame as seen in <a href="https://tools.ietf.org/html/rfc6455#section-5.2">RFC 6455. Sec 5.2</a>
 *
 * <pre>
 *    0                   1                   2                   3
 *    0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 *   +-+-+-+-+-------+-+-------------+-------------------------------+
 *   |F|R|R|R| opcode|M| Payload len |    Extended payload length    |
 *   |I|S|S|S|  (4)  |A|     (7)     |             (16/64)           |
 *   |N|V|V|V|       |S|             |   (if payload len==126/127)   |
 *   | |1|2|3|       |K|             |                               |
 *   +-+-+-+-+-------+-+-------------+ - - - - - - - - - - - - - - - +
 *   |     Extended payload length continued, if payload len == 127  |
 *   + - - - - - - - - - - - - - - - +-------------------------------+
 *   |                               |Masking-key, if MASK set to 1  |
 *   +-------------------------------+-------------------------------+
 *   | Masking-key (continued)       |          Payload Data         |
 *   +-------------------------------- - - - - - - - - - - - - - - - +
 *   :                     Payload Data continued ...                :
 *   + - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - +
 *   |                     Payload Data continued ...                |
 *   +---------------------------------------------------------------+
 * </pre>
 */
public class Frame
{
    public static Frame copyWithoutPayload(Frame original)
    {
        Frame copy = new Frame(original.getOpCode());
        copy.copyHeaders(original);
        return copy;
    }

    public static Frame copy(Frame original)
    {
        Frame copy = copyWithoutPayload(original);
        copy.setPayload(BufferUtil.copy(original.getPayload()));
        return copy;
    }

    /**
     * Maximum size of Control frame, per RFC 6455
     */
    public static final int MAX_CONTROL_PAYLOAD = 125;

    /**
     * Combined FIN + RSV1 + RSV2 + RSV3 + OpCode byte.
     *
     * <pre>
     *   1000_0000 (0x80) = fin
     *   0100_0000 (0x40) = rsv1
     *   0010_0000 (0x20) = rsv2
     *   0001_0000 (0x10) = rsv3
     *   0000_1111 (0x0F) = opcode
     * </pre>
     */
    protected byte finRsvOp;
    protected byte[] mask;

    /**
     * The payload data.
     * <p>
     * It is assumed to always be in FLUSH mode (ready to read) in this object.
     */
    protected ByteBuffer payload;

    /**
     * Construct form opcode
     *
     * @param opcode the opcode the frame is based on
     */
    public Frame(byte opcode)
    {
        this((byte)(0x80 | (opcode & 0x0F)), null, null);
    }

    public Frame(byte opCode, ByteBuffer payload)
    {
        this(opCode);
        setPayload(payload);
    }

    public Frame(byte opCode, String payload)
    {
        this(opCode);
        setPayload(payload);
    }

    public Frame(byte opCode, boolean fin, ByteBuffer payload)
    {
        this(opCode, payload);
        setFin(fin);
    }

    public Frame(byte opCode, boolean fin, String payload)
    {
        this(opCode, payload);
        setFin(fin);
    }

    protected Frame()
    {
        this(OpCode.UNDEFINED, null, null);
    }

    public Frame(byte finRsvOp, byte[] mask, ByteBuffer payload)
    {
        this.finRsvOp = finRsvOp;
        this.mask = mask;
        this.payload = payload;
    }

    public boolean isControlFrame()
    {
        return OpCode.isControlFrame(getOpCode());
    }

    public boolean isDataFrame()
    {
        return OpCode.isDataFrame(getOpCode());
    }

    protected void copyHeaders(Frame frame)
    {
        final byte opCode = (byte)(finRsvOp & 0x0F);
        finRsvOp = 0x00;
        finRsvOp |= frame.isFin() ? 0x80 : 0x00;
        finRsvOp |= frame.isRsv1() ? 0x40 : 0x00;
        finRsvOp |= frame.isRsv2() ? 0x20 : 0x00;
        finRsvOp |= frame.isRsv3() ? 0x10 : 0x00;
        finRsvOp |= opCode;
        if (frame.isMasked())
            mask = Arrays.copyOf(frame.getMask(), frame.getMask().length);
        else
            mask = null;
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
        {
            return true;
        }
        if (obj == null)
        {
            return false;
        }
        if (getClass() != obj.getClass())
        {
            return false;
        }
        Frame other = (Frame)obj;
        if (payload == null)
        {
            if (other.payload != null)
            {
                return false;
            }
        }
        else if (!payload.equals(other.payload))
        {
            return false;
        }
        if (finRsvOp != other.finRsvOp)
        {
            return false;
        }
        return Arrays.equals(mask, other.mask);
    }

    public byte[] getMask()
    {
        return mask;
    }

    public byte getOpCode()
    {
        return OpCode.getOpCode(finRsvOp);
    }

    /**
     * Get the payload ByteBuffer.
     */
    public ByteBuffer getPayload()
    {
        if (payload == null)
            return BufferUtil.EMPTY_BUFFER;

        return payload;
    }

    /**
     * Get the payload of the frame as a UTF-8 string.
     * <p>Should only be used in testing, does not validate the
     * UTF-8 and a non fin frame can contain partial UTF-8 characters.</p>
     *
     * @return the payload as a UTF-8 string.
     */
    public String getPayloadAsUTF8()
    {
        if (payload == null)
            return "";

        return BufferUtil.toUTF8String(payload);
    }

    public int getPayloadLength()
    {
        if (payload == null)
        {
            return 0;
        }
        return payload.remaining();
    }

    @Override
    public int hashCode()
    {
        final int prime = 31;
        int result = 1;
        result = (prime * result) + ((payload == null) ? 0 : payload.hashCode());
        result = (prime * result) + finRsvOp;
        result = (prime * result) + Arrays.hashCode(mask);
        return result;
    }

    public boolean hasPayload()
    {
        return (getPayload().remaining() > 0);
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

    public void reset()
    {
        finRsvOp = (byte)0x80; // FIN (!RSV, opcode 0)
        payload = null;
        mask = null;
    }

    public Frame setFin(boolean fin)
    {
        // set bit 1
        this.finRsvOp = (byte)((finRsvOp & 0x7F) | (fin ? 0x80 : 0x00));
        return this;
    }

    public Frame setMask(byte[] maskingKey)
    {
        this.mask = maskingKey;
        return this;
    }

    protected Frame setOpCode(byte op)
    {
        this.finRsvOp = (byte)((finRsvOp & 0xF0) | (op & 0x0F));
        return this;
    }

    /**
     * Set the data payload.
     * <p>
     * The provided buffer will be used as is, no copying of bytes performed.
     * <p>
     * The provided buffer should be flipped and ready to READ from.
     *
     * @param buf the bytebuffer to set
     * @return the frame itself
     */
    public Frame setPayload(ByteBuffer buf)
    {
        payload = buf;
        return this;
    }

    public Frame setPayload(String str)
    {
        setPayload(ByteBuffer.wrap(StringUtil.getUtf8Bytes(str)));
        return this;
    }

    public Frame setPayload(byte[] buf)
    {
        setPayload(ByteBuffer.wrap(buf));
        return this;
    }

    public Frame setRsv1(boolean rsv1)
    {
        // set bit 2
        this.finRsvOp = (byte)((finRsvOp & 0xBF) | (rsv1 ? 0x40 : 0x00));
        return this;
    }

    public Frame setRsv2(boolean rsv2)
    {
        // set bit 3
        this.finRsvOp = (byte)((finRsvOp & 0xDF) | (rsv2 ? 0x20 : 0x00));
        return this;
    }

    public Frame setRsv3(boolean rsv3)
    {
        // set bit 4
        this.finRsvOp = (byte)((finRsvOp & 0xEF) | (rsv3 ? 0x10 : 0x00));
        return this;
    }

    public Frame asReadOnly()
    {
        return new ReadOnly(this);
    }

    public boolean hasRsv()
    {
        return (finRsvOp & 0x70) != 0;
    }

    public void demask()
    {
        if (isMasked() && hasPayload())
        {
            int maskInt = 0;
            for (byte maskByte : mask)
            {
                maskInt = (maskInt << 8) + (maskByte & 0xFF);
            }

            int maskOffset = 0;

            int start = payload.position();
            int end = payload.limit();
            int offset = maskOffset;
            int remaining;
            while ((remaining = end - start) > 0)
            {
                if (remaining >= 4 && (offset & 3) == 0)
                {
                    payload.putInt(start, payload.getInt(start) ^ maskInt);
                    start += 4;
                    offset += 4;
                }
                else
                {
                    payload.put(start, (byte)(payload.get(start) ^ mask[offset & 3]));
                    ++start;
                    ++offset;
                }
            }

            Arrays.fill(mask, (byte)0);
        }
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
        b.append(",m=").append(mask == null ? "null" : TypeUtil.toHexString(mask));
        b.append(']');
        if (payload != null)
            b.append(BufferUtil.toDetailString(payload));
        return b.toString();
    }

    /**
     * Immutable, Read-only, Frame implementation.
     */
    private static class ReadOnly extends Frame
    {
        private ReadOnly(Frame frame)
        {
            super(frame.finRsvOp, frame.isMasked() ? frame.getMask() : null, frame.getPayload());
        }

        @Override
        public ByteBuffer getPayload()
        {
            ByteBuffer buffer = super.getPayload();
            if (buffer == null)
                return null;

            return buffer.asReadOnlyBuffer();
        }

        @Override
        protected void copyHeaders(Frame frame)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void reset()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public Frame setFin(boolean fin)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public Frame setMask(byte[] maskingKey)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        protected Frame setOpCode(byte op)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public Frame setPayload(ByteBuffer buf)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public Frame setPayload(String str)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public Frame setPayload(byte[] buf)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public Frame setRsv1(boolean rsv1)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public Frame setRsv2(boolean rsv2)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public Frame setRsv3(boolean rsv3)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public Frame asReadOnly()
        {
            return this;
        }
    }
}
