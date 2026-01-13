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

package org.eclipse.jetty.quic.common.internal.frames;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.frames.CryptoFrame;
import org.eclipse.jetty.quic.api.frames.Frame;
import org.eclipse.jetty.quic.util.VarLenInt;

public class CryptoFrameParser implements FrameParser
{
    private final VarLenInt varLenInt;
    private State state = State.FRAME_TYPE;
    private long offset;
    private long length;

    public CryptoFrameParser(VarLenInt varLenInt)
    {
        this.varLenInt = varLenInt;
    }

    @Override
    public Frame parse(RetainableByteBuffer buffer)
    {
        while (true)
        {
            int remaining = buffer.remaining();
            if (remaining == 0)
                return null;
            ByteBuffer byteBuffer = buffer.getByteBuffer();
            switch (state)
            {
                case FRAME_TYPE ->
                {
                    byteBuffer.get();
                    state = State.OFFSET;
                }
                case OFFSET ->
                {
                    if (varLenInt.tryDecode(byteBuffer, offset -> this.offset = offset))
                        state = State.LENGTH;
                }
                case LENGTH ->
                {
                    if (varLenInt.tryDecode(byteBuffer, length -> this.length = length))
                        state = State.DATA;
                }
                case DATA ->
                {
                    if (remaining < length)
                    {
                        // Should not happen, as QUIC packets fit UDP datagrams.
                        throw new BufferUnderflowException();
                    }
                    else
                    {
                        RetainableByteBuffer slice = buffer.slice(length);
                        buffer.skip(length);
                        CryptoFrame frame = new CryptoFrame(offset, slice);
                        state = State.FRAME_TYPE;
                        offset = 0;
                        length = 0;
                        return frame;
                    }
                }
            }
        }
    }

    private enum State
    {
        FRAME_TYPE,
        OFFSET,
        LENGTH,
        DATA
    }
}
