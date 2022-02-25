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

package org.eclipse.jetty.util;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.Utf8Appendable.NotUtf8Exception;

/**
 * Stateful parser for lines of UTF8 formatted text, looking for <code>"\n"</code> as a line termination character.
 * <p>
 * For use with new IO framework that is based on ByteBuffer parsing.
 */
public class Utf8LineParser
{
    private enum State
    {
        START,
        PARSE,
        END;
    }

    private State state;
    private Utf8StringBuilder utf;

    public Utf8LineParser()
    {
        this.state = State.START;
    }

    /**
     * Parse a ByteBuffer (could be a partial buffer), and return once a complete line of UTF8 parsed text has been reached.
     *
     * @param buf the buffer to parse (could be an incomplete buffer)
     * @return the line of UTF8 parsed text, or null if no line end termination has been reached within the {@link ByteBuffer#remaining() remaining} bytes of
     * the provided ByteBuffer. (In the case of a null, a subsequent ByteBuffer with a line end termination should be provided)
     * @throws NotUtf8Exception if the input buffer has bytes that do not conform to UTF8 validation (validation performed by {@link Utf8StringBuilder}
     */
    public String parse(ByteBuffer buf)
    {
        byte b;
        while (buf.remaining() > 0)
        {
            b = buf.get();
            if (parseByte(b))
            {
                state = State.START;
                return utf.toString();
            }
        }
        // have not reached end of line (yet)
        return null;
    }

    private boolean parseByte(byte b)
    {
        switch (state)
        {
            case START:
                utf = new Utf8StringBuilder();
                state = State.PARSE;
                return parseByte(b);

            case PARSE:
                // not waiting on more UTF sequence parts.
                if (utf.isUtf8SequenceComplete() && ((b == '\r') || (b == '\n')))
                {
                    state = State.END;
                    return parseByte(b);
                }
                utf.append(b);
                return false;

            case END:
                if (b == '\n')
                {
                    // we've reached the end
                    state = State.START;
                    return true;
                }
                return false;

            default:
                throw new IllegalStateException();
        }
    }
}
