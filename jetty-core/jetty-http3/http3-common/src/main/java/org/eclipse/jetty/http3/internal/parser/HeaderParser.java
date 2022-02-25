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

package org.eclipse.jetty.http3.internal.parser;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http3.internal.VarLenInt;

/**
 * <p>The parser for the frame header of HTTP/3 frames.</p>
 *
 * @see MessageParser
 */
public class HeaderParser
{
    // TODO: RateControl?
    private final VarLenInt varLenInt = new VarLenInt();
    private State state = State.TYPE;
    private long type;
    private long length;

    public void reset()
    {
        varLenInt.reset();
        state = State.TYPE;
        type = 0;
        length = 0;
    }

    /**
     * <p>Parses the frame header bytes in the given {@code buffer}; only the frame header
     * bytes are consumed, therefore when this method returns, the buffer may
     * contain the unconsumed bytes of the frame body (or other frames).</p>
     *
     * @param buffer the buffer to parse
     * @return true if all the frame header bytes were parsed, false if not enough
     * frame header bytes were present in the buffer
     */
    public boolean parse(ByteBuffer buffer)
    {
        while (buffer.hasRemaining())
        {
            switch (state)
            {
                case TYPE:
                {
                    if (varLenInt.decode(buffer, v -> type = v))
                    {
                        state = State.LENGTH;
                        break;
                    }
                    return false;
                }
                case LENGTH:
                {
                    if (varLenInt.decode(buffer, v -> length = v))
                    {
                        state = State.TYPE;
                        return true;
                    }
                    return false;
                }
                default:
                {
                    throw new IllegalStateException();
                }
            }
        }
        return false;
    }

    public long getFrameType()
    {
        return type;
    }

    public long getFrameLength()
    {
        return length;
    }

    private enum State
    {
        TYPE, LENGTH
    }
}
