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

import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

/// An immutable websocket frame.
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

    /// Acquires the [RetainableByteBuffer] with the payload bytes of this frame.
    ///
    /// The buffer is retained and must be released or closed.
    ///
    /// Reading the buffer consumes the payload.
    ///
    /// @return the [RetainableByteBuffer] with the payload bytes of this frame
    RetainableByteBuffer acquire();

    /// @return the current payload length
    long getPayloadLength();

    Type getType();

    boolean hasPayload();

    boolean isFin();

    boolean isMasked();

    boolean isRsv1();

    boolean isRsv2();

    boolean isRsv3();

    default CloseStatus getCloseStatus()
    {
        return null;
    }

    record CloseStatus(int statusCode, String reason)
    {
    }

    /// The effective opcode of the frame accounting for the CONTINUATION opcode.
    /// If the frame is a CONTINUATION frame for a TEXT message, this will return TEXT.
    /// If the frame is a CONTINUATION frame for a BINARY message, this will return BINARY.
    /// Otherwise, this will return the same opcode as the frame.
    ///
    /// @return the effective opcode of the frame.
    byte getEffectiveOpCode();
}
