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

import org.eclipse.jetty.quic.api.frames.ResetFrame;
import org.eclipse.jetty.quic.util.VarLenInt;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

public class ResetStreamParser
{
    private final VarLenInt varLenInt;
    private State state = State.FRAME_TYPE;
    private long streamId;
    private long errorCode;
    private long finalSize;

    public ResetStreamParser(VarLenInt varLenInt)
    {
        this.varLenInt = varLenInt;
    }

    public ResetFrame parse(RetainableByteBuffer buffer)
    {
        while (buffer.hasRemaining())
        {
            switch (state)
            {
                case FRAME_TYPE ->
                {
                    buffer.get();
                    state = State.STREAM_ID;
                }
                case STREAM_ID ->
                {
                    if (varLenInt.tryDecode(buffer, v -> streamId = v))
                        state = State.ERROR_CODE;
                }
                case ERROR_CODE ->
                {
                    if (varLenInt.tryDecode(buffer, v -> errorCode = v))
                        state = State.FINAL_SIZE;
                }
                case FINAL_SIZE ->
                {
                    if (varLenInt.tryDecode(buffer, v -> finalSize = v))
                        return result();
                }
            }
        }
        return null;
    }

    private ResetFrame result()
    {
        ResetFrame frame = new ResetFrame(streamId, errorCode, finalSize);
        state = State.FRAME_TYPE;
        streamId = 0;
        errorCode = 0;
        finalSize = 0;
        return frame;
    }

    private enum State
    {
        FRAME_TYPE, STREAM_ID, ERROR_CODE, FINAL_SIZE
    }
}
