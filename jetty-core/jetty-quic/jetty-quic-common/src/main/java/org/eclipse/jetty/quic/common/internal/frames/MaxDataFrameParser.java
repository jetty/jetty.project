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
import org.eclipse.jetty.quic.api.frames.MaxDataFrame;
import org.eclipse.jetty.quic.util.VarLenInt;

public class MaxDataFrameParser implements FrameParser
{
    private final VarLenInt varLenInt;
    private State state = State.FRAME_TYPE;
    private long maxData;

    public MaxDataFrameParser(VarLenInt varLenInt)
    {
        this.varLenInt = varLenInt;
    }

    @Override
    public MaxDataFrame parse(RetainableByteBuffer buffer)
    {
        ByteBuffer byteBuffer = buffer.getByteBuffer();
        while (byteBuffer.hasRemaining())
        {
            switch (state)
            {
                case FRAME_TYPE ->
                {
                    byteBuffer.get();
                    state = State.MAX_DATA;
                }
                case MAX_DATA ->
                {
                    if (varLenInt.tryDecode(byteBuffer, v -> maxData = v))
                        return result();
                }
            }
        }
        return null;
    }

    private MaxDataFrame result()
    {
        MaxDataFrame frame = new MaxDataFrame(maxData);
        state = State.FRAME_TYPE;
        maxData = 0;
        return frame;
    }

    private enum State
    {
        FRAME_TYPE, MAX_DATA
    }
}
