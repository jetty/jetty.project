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

import org.eclipse.jetty.spdy.frames.DataFrame;

public abstract class DataFrameParser
{
    private State state = State.STREAM_ID;
    private int cursor;
    private int streamId;
    private byte flags;
    private int length;
    private int remaining;
    private ByteBuffer data;

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
                        state = State.FLAGS;
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
                        state = State.FLAGS;
                    break;
                }
                case FLAGS:
                {
                    flags = buffer.get();
                    cursor = 3;
                    state = State.LENGTH;
                    break;
                }
                case LENGTH:
                {
                    byte currByte = buffer.get();
                    --cursor;
                    length += (currByte & 0xFF) << 8 * cursor;
                    if (cursor > 0)
                        break;
                    remaining = length;
                    state = State.DATA;
                    // Fall down if length == 0: we can't loop because the buffer
                    // may be empty but we need to invoke the application anyway
                    if (length > 0)
                        break;
                }
                case DATA:
                {
                    // Length can only be at most 3 bytes, which is 16_777_215 i.e. 16 MiB.
                    // However, compliant clients should implement flow control, so it's
                    // unlikely that we will get that 16 MiB chunk.
                    // However, TCP may further split the flow control window, so we may
                    // only have part of the data at this point.

                    // TODO: introduce synthetic frames instead of accumulating data

                    int length = Math.min(remaining, buffer.remaining());
                    int limit = buffer.limit();
                    buffer.limit(buffer.position() + length);
                    ByteBuffer bytes = buffer.slice();
                    buffer.limit(limit);
                    buffer.position(buffer.position() + length);
                    remaining -= length;
                    if (remaining == 0)
                    {
                        if (data == null)
                        {
                            onData(bytes);
                            return true;
                        }
                        else
                        {
                            accumulate(bytes);
                            onData(data);
                            return true;
                        }
                    }
                    else
                    {
                        // We got only part of the frame data bytes, so we need to copy
                        // the current data and wait for the remaining to arrive.
                        if (data == null)
                            data = bytes;
                        else
                            accumulate(bytes);
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

    private void accumulate(ByteBuffer bytes)
    {
        ByteBuffer local = ByteBuffer.allocate(data.remaining() + bytes.remaining());
        local.put(data).put(bytes);
        local.flip();
        data = local;
    }

    private void onData(ByteBuffer bytes)
    {
        DataFrame frame = new DataFrame(streamId, flags, bytes.remaining());
        onDataFrame(frame, bytes);
        reset();
    }

    protected abstract void onDataFrame(DataFrame frame, ByteBuffer data);

    private void reset()
    {
        state = State.STREAM_ID;
        cursor = 0;
        streamId = 0;
        flags = 0;
        length = 0;
        remaining = 0;
        data = null;
    }

    private enum State
    {
        STREAM_ID, STREAM_ID_BYTES, FLAGS, LENGTH, DATA
    }
}
