/*
 * Copyright (c) 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
