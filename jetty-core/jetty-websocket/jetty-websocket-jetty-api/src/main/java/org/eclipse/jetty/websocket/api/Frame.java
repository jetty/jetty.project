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

package org.eclipse.jetty.websocket.api;

import java.nio.ByteBuffer;

/**
 * An immutable websocket frame.
 */
public interface Frame
{
    enum Type
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

        private final byte opcode;

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

    byte[] getMask();

    byte getOpCode();

    ByteBuffer getPayload();

    /**
     * The original payload length ({@link ByteBuffer#remaining()})
     *
     * @return the original payload length ({@link ByteBuffer#remaining()})
     */
    int getPayloadLength();

    Type getType();

    boolean hasPayload();

    boolean isFin();

    boolean isMasked();

    boolean isRsv1();

    boolean isRsv2();

    boolean isRsv3();

    /**
     * The effective opcode of the frame accounting for the CONTINUATION opcode.
     * If the frame is a CONTINUATION frame for a TEXT message, this will return TEXT.
     * If the frame is a CONTINUATION frame for a BINARY message, this will return BINARY.
     * Otherwise, this will return the same opcode as the frame.
     * @return the effective opcode of the frame.
     */
    byte getEffectiveOpCode();

    class Wrapper implements Frame
    {
        private final Frame _frame;

        public Wrapper(Frame frame)
        {
            _frame = frame;
        }

        @Override
        public byte[] getMask()
        {
            return _frame.getMask();
        }

        @Override
        public byte getOpCode()
        {
            return _frame.getOpCode();
        }

        @Override
        public ByteBuffer getPayload()
        {
            return _frame.getPayload();
        }

        @Override
        public int getPayloadLength()
        {
            return _frame.getPayloadLength();
        }

        @Override
        public Type getType()
        {
            return _frame.getType();
        }

        @Override
        public boolean hasPayload()
        {
            return _frame.hasPayload();
        }

        @Override
        public boolean isFin()
        {
            return _frame.isFin();
        }

        @Override
        public boolean isMasked()
        {
            return _frame.isMasked();
        }

        @Override
        public boolean isRsv1()
        {
            return _frame.isRsv1();
        }

        @Override
        public boolean isRsv2()
        {
            return _frame.isRsv2();
        }

        @Override
        public boolean isRsv3()
        {
            return _frame.isRsv3();
        }

        @Override
        public byte getEffectiveOpCode()
        {
            return _frame.getEffectiveOpCode();
        }
    }

    static Frame copy(Frame frame)
    {
        ByteBuffer payloadCopy = copy(frame.getPayload());
        return new Frame.Wrapper(frame)
        {
            @Override
            public ByteBuffer getPayload()
            {
                return payloadCopy;
            }

            @Override
            public int getPayloadLength()
            {
                return payloadCopy == null ? 0 : payloadCopy.remaining();
            }
        };
    }

    private static ByteBuffer copy(ByteBuffer buffer)
    {
        if (buffer == null)
            return null;
        int p = buffer.position();
        ByteBuffer clone = buffer.isDirect() ? ByteBuffer.allocateDirect(buffer.remaining()) : ByteBuffer.allocate(buffer.remaining());
        clone.put(buffer);
        clone.flip();
        buffer.position(p);
        return clone;
    }
}
