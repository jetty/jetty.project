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

import org.eclipse.jetty.spdy.CompressionFactory;
import org.eclipse.jetty.spdy.StreamException;
import org.eclipse.jetty.spdy.api.Headers;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.api.StreamStatus;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.frames.ControlFrameType;
import org.eclipse.jetty.spdy.frames.SynStreamFrame;

public class SynStreamBodyParser extends ControlFrameBodyParser
{
    private final Headers headers = new Headers();
    private final ControlFrameParser controlFrameParser;
    private final HeadersBlockParser headersBlockParser;
    private State state = State.STREAM_ID;
    private int cursor;
    private int streamId;
    private int associatedStreamId;
    private byte priority;

    public SynStreamBodyParser(CompressionFactory.Decompressor decompressor, ControlFrameParser controlFrameParser)
    {
        this.controlFrameParser = controlFrameParser;
        this.headersBlockParser = new SynStreamHeadersBlockParser(decompressor);
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
                        state = State.ASSOCIATED_STREAM_ID;
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
                        state = State.ASSOCIATED_STREAM_ID;
                    }
                    break;
                }
                case ASSOCIATED_STREAM_ID:
                {
                    // Now we know the streamId, we can do the version check
                    // and if it is wrong, issue a RST_STREAM
                    checkVersion(controlFrameParser.getVersion(), streamId);

                    if (buffer.remaining() >= 4)
                    {
                        associatedStreamId = buffer.getInt() & 0x7F_FF_FF_FF;
                        state = State.PRIORITY;
                    }
                    else
                    {
                        state = State.ASSOCIATED_STREAM_ID_BYTES;
                        cursor = 4;
                    }
                    break;
                }
                case ASSOCIATED_STREAM_ID_BYTES:
                {
                    byte currByte = buffer.get();
                    --cursor;
                    associatedStreamId += (currByte & 0xFF) << 8 * cursor;
                    if (cursor == 0)
                    {
                        associatedStreamId &= 0x7F_FF_FF_FF;
                        state = State.PRIORITY;
                    }
                    break;
                }
                case PRIORITY:
                {
                    byte currByte = buffer.get();
                    ++cursor;
                    if (cursor == 1)
                    {
                        priority = readPriority(controlFrameParser.getVersion(), currByte);
                    }
                    else
                    {
                        // Unused byte after priority, skip it
                        cursor = 0;
                        state = State.HEADERS;
                    }
                    break;
                }
                case HEADERS:
                {
                    short version = controlFrameParser.getVersion();
                    int length = controlFrameParser.getLength() - 10;
                    if (headersBlockParser.parse(streamId, version, length, buffer))
                    {
                        byte flags = controlFrameParser.getFlags();
                        // TODO: can it be both FIN and UNIDIRECTIONAL ?
                        if (flags != 0 && flags != SynInfo.FLAG_CLOSE && flags != SynInfo.FLAG_UNIDIRECTIONAL)
                            throw new IllegalArgumentException("Invalid flag " + flags + " for frame " + ControlFrameType.SYN_STREAM);

                        SynStreamFrame frame = new SynStreamFrame(version, flags, streamId, associatedStreamId, priority, new Headers(headers, true));
                        controlFrameParser.onControlFrame(frame);

                        reset();
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

    private void checkVersion(short version, int streamId)
    {
        if (version != SPDY.V2 && version != SPDY.V3)
            throw new StreamException(streamId, StreamStatus.UNSUPPORTED_VERSION);
    }

    private byte readPriority(short version, byte currByte)
    {
        // Right shift retains the sign bit when operated on a byte,
        // so we use an int to perform the shifts
        switch (version)
        {
            case SPDY.V2:
                int p2 = currByte & 0b1100_0000;
                p2 >>>= 6;
                return (byte)p2;
            case SPDY.V3:
                int p3 = currByte & 0b1110_0000;
                p3 >>>= 5;
                return (byte)p3;
            default:
                throw new IllegalStateException();
        }
    }

    private void reset()
    {
        headers.clear();
        state = State.STREAM_ID;
        cursor = 0;
        streamId = 0;
        associatedStreamId = 0;
        priority = 0;
    }

    private enum State
    {
        STREAM_ID, STREAM_ID_BYTES, ASSOCIATED_STREAM_ID, ASSOCIATED_STREAM_ID_BYTES, PRIORITY, HEADERS
    }

    private class SynStreamHeadersBlockParser extends HeadersBlockParser
    {
        public SynStreamHeadersBlockParser(CompressionFactory.Decompressor decompressor)
        {
            super(decompressor);
        }

        @Override
        protected void onHeader(String name, String[] values)
        {
            for (String value : values)
                headers.add(name, value);
        }
    }
}
