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

package org.eclipse.jetty.websocket.core.frames;

import java.nio.ByteBuffer;
import java.util.Arrays;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.core.ProtocolException;

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
    public enum Type
    {
        CONTINUATION((byte)0x00),
        TEXT((byte)0x01),
        BINARY((byte)0x02),
        CLOSE((byte)0x08),
        PING((byte)0x09),
        PONG((byte)0x0A);

        public static Type from(byte op)
        {
            for (Type type : values())
            {
                if (type.opcode == op)
                {
                    return type;
                }
            }
            throw new IllegalArgumentException("OpCode " + op + " is not a valid Frame.Type");
        }

        private byte opcode;

        Type(byte code)
        {
            this.opcode = code;
        }

        public byte getOpCode()
        {
            return opcode;
        }

        public boolean isControl()
        {
            return (opcode >= CLOSE.getOpCode());
        }

        public boolean isData()
        {
            return (opcode == TEXT.getOpCode()) || (opcode == BINARY.getOpCode());
        }

        public boolean isContinuation()
        {
            return opcode == CONTINUATION.getOpCode();
        }

        @Override
        public String toString()
        {
            return this.name();
        }
    }

    public boolean isControlFrame()
    {
        return getType().isControl();
    }

    public boolean isDataFrame()
    {
        return !isControlFrame();
    }





    public static Frame copyWithoutPayload(Frame original)
    {
        Frame copy = new Frame(original.getOpCode());
        copy.copyHeaders(original);
        return copy;
    }

    public static Frame copy(Frame original)
    {
        Frame copy = copyWithoutPayload(original);
        ByteBuffer payload = original.getPayload();
        if (payload != null)
        {
            ByteBuffer payloadCopy = ByteBuffer.allocate(payload.remaining());
            payloadCopy.put(payload.slice()).flip();
            copy.setPayload(payloadCopy);
        }
        return copy;
    }

    /** Maximum size of Control frame, per RFC 6455 */
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
    protected boolean masked = false;

    protected byte mask[];
    /**
     * The payload data.
     * <p>
     * It is assumed to always be in FLUSH mode (ready to read) in this object.
     */
    protected ByteBuffer payload;

    /**
     * Construct form opcode
     * @param opcode the opcode the frame is based on
     */
    public Frame(byte opcode)
    {
        finRsvOp = (byte)(0x80 | (opcode & 0x0F)); // FIN (!RSV, opcode 0)
        masked = false;
        payload = null;
        mask = null;
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
        this(OpCode.UNDEFINED);
    }


    public void assertValid()
    {
        if (isControlFrame())
        {
            if (getPayloadLength() > MAX_CONTROL_PAYLOAD)
            {
                throw new ProtocolException("Desired payload length [" + getPayloadLength() + "] exceeds maximum control payload length ["
                        + MAX_CONTROL_PAYLOAD + "]");
            }

            if ((finRsvOp & 0x80) == 0)
            {
                throw new ProtocolException("Cannot have FIN==false on Control frames");
            }

            if ((finRsvOp & 0x40) != 0)
            {
                throw new ProtocolException("Cannot have RSV1==true on Control frames");
            }

            if ((finRsvOp & 0x20) != 0)
            {
                throw new ProtocolException("Cannot have RSV2==true on Control frames");
            }

            if ((finRsvOp & 0x10) != 0)
            {
                throw new ProtocolException("Cannot have RSV3==true on Control frames");
            }
        }
    }

    protected void copyHeaders(Frame frame)
    {
        byte opCode = (byte)(finRsvOp & 0x0F);
        finRsvOp = 0x00;
        finRsvOp |= frame.isFin()?0x80:0x00;
        finRsvOp |= frame.isRsv1()?0x40:0x00;
        finRsvOp |= frame.isRsv2()?0x20:0x00;
        finRsvOp |= frame.isRsv3()?0x10:0x00;
        finRsvOp |= opCode;

        masked = frame.isMasked();
        if (masked)
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
        if (masked != other.masked)
        {
            return false;
        }
        if (!Arrays.equals(mask,other.mask))
        {
            return false;
        }
        return true;
    }

    public byte[] getMask()
    {
        return mask;
    }

    public byte getOpCode()
    {
        return (byte)(finRsvOp & 0x0F);
    }

    /**
     * Get the payload ByteBuffer. possible null.
     */
    public ByteBuffer getPayload()
    {
        return payload;
    }

    public String getPayloadAsUTF8()
    {
        if (payload == null)
            return null;

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

    public Type getType()
    {
        return Type.from(getOpCode());
    }

    @Override
    public int hashCode()
    {
        final int prime = 31;
        int result = 1;
        result = (prime * result) + ((payload == null)?0: payload.hashCode());
        result = (prime * result) + finRsvOp;
        result = (prime * result) + Arrays.hashCode(mask);
        return result;
    }

    public boolean hasPayload()
    {
        return ((payload != null) && payload.hasRemaining());
    }

    public boolean isFin()
    {
        return (byte)(finRsvOp & 0x80) != 0;
    }

    public boolean isMasked()
    {
        return masked;
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
        masked = false;
        payload = null;
        mask = null;
    }

    public Frame setFin(boolean fin)
    {
        // set bit 1
        this.finRsvOp = (byte)((finRsvOp & 0x7F) | (fin?0x80:0x00));
        return this;
    }

    public Frame setMask(byte[] maskingKey)
    {
        this.mask = maskingKey;
        this.masked = (mask != null);
        return this;
    }

    public Frame setMasked(boolean mask)
    {
        this.masked = mask;
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
     * @param buf the bytebuffer to set
     * @return the frame itself
     */
    public Frame setPayload(ByteBuffer buf)
    {
        if(isControlFrame())
        {
            // RFC-6455 Spec Required Control Frame validation.
            if (buf != null && buf.remaining() > MAX_CONTROL_PAYLOAD)
                throw new ProtocolException("Control Payloads can not exceed " + MAX_CONTROL_PAYLOAD + " bytes in length.");
        }

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
        this.finRsvOp = (byte)((finRsvOp & 0xBF) | (rsv1?0x40:0x00));
        return this;
    }

    public Frame setRsv2(boolean rsv2)
    {
        // set bit 3
        this.finRsvOp = (byte)((finRsvOp & 0xDF) | (rsv2?0x20:0x00));
        return this;
    }

    public Frame setRsv3(boolean rsv3)
    {
        // set bit 4
        this.finRsvOp = (byte)((finRsvOp & 0xEF) | (rsv3?0x10:0x00));
        return this;
    }

    public Frame asReadOnly()
    {
        return new ReadOnlyFrame(this);
    }

    @Override
    public String toString()
    {
        StringBuilder b = new StringBuilder();
        b.append(OpCode.name((byte)(finRsvOp & 0x0F)));
        b.append('@');
        b.append(Integer.toHexString(super.hashCode()));
        b.append('[');
        b.append("len=").append(getPayloadLength());
        b.append(",fin=").append((finRsvOp & 0x80) != 0);
        b.append(",rsv=");
        b.append(((finRsvOp & 0x40) != 0)?'1':'0');
        b.append(((finRsvOp & 0x20) != 0)?'1':'0');
        b.append(((finRsvOp & 0x10) != 0)?'1':'0');
        b.append(",masked=").append(masked);
        b.append(']');
        return b.toString();
    }
}
