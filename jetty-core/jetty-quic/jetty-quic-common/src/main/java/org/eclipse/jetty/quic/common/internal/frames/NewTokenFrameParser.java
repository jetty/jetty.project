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

import java.nio.ByteBuffer;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.frames.Frame;
import org.eclipse.jetty.quic.api.frames.NewTokenFrame;
import org.eclipse.jetty.quic.util.VarLenInt;

public class NewTokenFrameParser implements FrameParser
{
    private final VarLenInt varLenInt;
    private State state = State.FRAME_TYPE;
    private int length;
    private byte[] token;

    public NewTokenFrameParser(VarLenInt varLenInt)
    {
        this.varLenInt = varLenInt;
    }

    @Override
    public Frame parse(RetainableByteBuffer buffer)
    {
        ByteBuffer byteBuffer = buffer.getByteBuffer();
        while (byteBuffer.hasRemaining())
        {
            switch (state)
            {
                case FRAME_TYPE ->
                {
                    byteBuffer.get();
                    state = State.TOKEN_LENGTH;
                }
                case TOKEN_LENGTH ->
                {
                    if (varLenInt.tryDecode(byteBuffer, v -> length = Math.toIntExact(v)))
                    {
                        token = new byte[length];
                        state = State.TOKEN;
                    }
                }
                case TOKEN ->
                {
                    int remaining = Math.min(byteBuffer.remaining(), length);
                    byteBuffer.get(token, token.length - length, remaining);
                    length -= remaining;
                    if (length == 0)
                        return result();
                }
            }
        }
        return null;
    }

    private NewTokenFrame result()
    {
        NewTokenFrame frame = new NewTokenFrame(token);
        state = State.FRAME_TYPE;
        length = 0;
        token = null;
        return frame;
    }

    private enum State
    {
        FRAME_TYPE, TOKEN_LENGTH, TOKEN
    }
}
