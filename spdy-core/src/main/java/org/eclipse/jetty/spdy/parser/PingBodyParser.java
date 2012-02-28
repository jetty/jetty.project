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

import org.eclipse.jetty.spdy.frames.PingFrame;

public class PingBodyParser extends ControlFrameBodyParser
{
    private final ControlFrameParser controlFrameParser;
    private State state = State.PING_ID;
    private int cursor;
    private int pingId;

    public PingBodyParser(ControlFrameParser controlFrameParser)
    {
        this.controlFrameParser = controlFrameParser;
    }

    @Override
    public boolean parse(ByteBuffer buffer)
    {
        while (buffer.hasRemaining())
        {
            switch (state)
            {
                case PING_ID:
                {
                    if (buffer.remaining() >= 4)
                    {
                        pingId = buffer.getInt() & 0x7F_FF_FF_FF;
                        onPing();
                        return true;
                    }
                    else
                    {
                        state = State.PING_ID_BYTES;
                        cursor = 4;
                    }
                    break;
                }
                case PING_ID_BYTES:
                {
                    byte currByte = buffer.get();
                    --cursor;
                    pingId += (currByte & 0xFF) << 8 * cursor;
                    if (cursor == 0)
                    {
                        onPing();
                        return true;
                    }
                    break;
                }
                default:
                {
                    throw new IllegalStateException();
                }
            }
        }
        return false;
    }

    private void onPing()
    {
        PingFrame frame = new PingFrame(controlFrameParser.getVersion(), pingId);
        controlFrameParser.onControlFrame(frame);
        reset();
    }

    private void reset()
    {
        state = State.PING_ID;
        cursor = 0;
        pingId = 0;
    }

    private enum State
    {
        PING_ID, PING_ID_BYTES
    }
}
