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

import org.eclipse.jetty.spdy.frames.WindowUpdateFrame;

public class WindowUpdateBodyParser extends ControlFrameBodyParser
{
    private final ControlFrameParser controlFrameParser;
    private State state = State.STREAM_ID;
    private int cursor;
    private int streamId;
    private int windowDelta;

    public WindowUpdateBodyParser(ControlFrameParser controlFrameParser)
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
                case STREAM_ID:
                {
                    if (buffer.remaining() >= 4)
                    {
                        streamId = buffer.getInt() & 0x7F_FF_FF_FF;
                        state = State.WINDOW_DELTA;
                    }
                    else
                    {
                        state = State.STREAM_ID_BYTES;
                        cursor = 4;
                    }
                    break;
                }
                case STREAM_ID_BYTES:
                {
                    byte currByte = buffer.get();
                    --cursor;
                    streamId += (currByte & 0xFF) << 8 * cursor;
                    if (cursor == 0)
                    {
                        streamId &= 0x7F_FF_FF_FF;
                        state = State.WINDOW_DELTA;
                    }
                    break;
                }
                case WINDOW_DELTA:
                {
                    if (buffer.remaining() >= 4)
                    {
                        windowDelta = buffer.getInt() & 0x7F_FF_FF_FF;
                        onWindowUpdate();
                        return true;
                    }
                    else
                    {
                        state = State.WINDOW_DELTA_BYTES;
                        cursor = 4;
                    }
                    break;
                }
                case WINDOW_DELTA_BYTES:
                {
                    byte currByte = buffer.get();
                    --cursor;
                    windowDelta += (currByte & 0xFF) << 8 * cursor;
                    if (cursor == 0)
                    {
                        windowDelta &= 0x7F_FF_FF_FF;
                        onWindowUpdate();
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

    private void onWindowUpdate()
    {
        WindowUpdateFrame frame = new WindowUpdateFrame(controlFrameParser.getVersion(), streamId, windowDelta);
        controlFrameParser.onControlFrame(frame);
        reset();
    }

    private void reset()
    {
        state = State.STREAM_ID;
        cursor = 0;
        streamId = 0;
        windowDelta = 0;
    }

    private enum State
    {
        STREAM_ID, STREAM_ID_BYTES, WINDOW_DELTA, WINDOW_DELTA_BYTES;
    }
}
