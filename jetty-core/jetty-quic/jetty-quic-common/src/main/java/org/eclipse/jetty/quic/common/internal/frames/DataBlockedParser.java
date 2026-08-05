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

import org.eclipse.jetty.quic.api.frames.DataBlockedFrame;
import org.eclipse.jetty.quic.util.VarLenInt;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

public class DataBlockedParser
{
    private final VarLenInt varLenInt;
    private State state = State.FRAME_TYPE;
    private long maxData;

    public DataBlockedParser(VarLenInt varLenInt)
    {
        this.varLenInt = varLenInt;
    }

    public DataBlockedFrame parse(RetainableByteBuffer buffer)
    {
        while (buffer.hasRemaining())
        {
            switch (state)
            {
                case FRAME_TYPE ->
                {
                    buffer.get();
                    state = State.MAX_DATA;
                }
                case MAX_DATA ->
                {
                    if (varLenInt.tryDecode(buffer, v -> maxData = v))
                        return result();
                }
            }
        }
        return null;
    }

    private DataBlockedFrame result()
    {
        DataBlockedFrame frame = new DataBlockedFrame(maxData);
        state = State.FRAME_TYPE;
        maxData = 0;
        return frame;
    }

    private enum State
    {
        FRAME_TYPE, MAX_DATA
    }
}
