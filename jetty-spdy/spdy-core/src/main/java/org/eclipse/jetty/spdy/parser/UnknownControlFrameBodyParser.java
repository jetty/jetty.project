//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.spdy.parser;

import java.nio.ByteBuffer;

public class UnknownControlFrameBodyParser extends ControlFrameBodyParser
{
    private final ControlFrameParser controlFrameParser;
    private State state = State.BODY;
    private int remaining;

    public UnknownControlFrameBodyParser(ControlFrameParser controlFrameParser)
    {
        this.controlFrameParser = controlFrameParser;
    }

    @Override
    public boolean parse(ByteBuffer buffer)
    {
        switch (state)
        {
            case BODY:
            {
                remaining = controlFrameParser.getLength();
                state = State.CONSUME;
                // Fall down
            }
            case CONSUME:
            {
                int consume = Math.min(remaining, buffer.remaining());
                buffer.position(buffer.position() + consume);
                remaining -= consume;
                if (remaining > 0)
                    return false;
                reset();
                return true;
            }
            default:
            {
                throw new IllegalStateException();
            }
        }
    }

    private void reset()
    {
        state = State.BODY;
        remaining = 0;
    }

    private enum State
    {
        BODY, CONSUME
    }
}
