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
import org.eclipse.jetty.spdy.api.Headers;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.frames.ControlFrameType;
import org.eclipse.jetty.spdy.frames.SynReplyFrame;

public class SynReplyBodyParser extends ControlFrameBodyParser
{
    private final Headers headers = new Headers();
    private final ControlFrameParser controlFrameParser;
    private final HeadersBlockParser headersBlockParser;
    private State state = State.STREAM_ID;
    private int cursor;
    private int streamId;

    public SynReplyBodyParser(CompressionFactory.Decompressor decompressor, ControlFrameParser controlFrameParser)
    {
        this.controlFrameParser = controlFrameParser;
        this.headersBlockParser = new SynReplyHeadersBlockParser(decompressor);
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
                        state = State.ADDITIONAL;
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
                        state = State.ADDITIONAL;
                    }
                    break;
                }
                case ADDITIONAL:
                {
                    switch (controlFrameParser.getVersion())
                    {
                        case SPDY.V2:
                        {
                            if (buffer.remaining() >= 2)
                            {
                                buffer.getShort();
                                state = State.HEADERS;
                            }
                            else
                            {
                                state = State.ADDITIONAL_BYTES;
                                cursor = 2;
                            }
                            break;
                        }
                        case SPDY.V3:
                        {
                            state = State.HEADERS;
                            break;
                        }
                        default:
                        {
                            throw new IllegalStateException();
                        }
                    }
                    break;
                }
                case ADDITIONAL_BYTES:
                {
                    assert controlFrameParser.getVersion() == SPDY.V2;
                    buffer.get();
                    --cursor;
                    if (cursor == 0)
                        state = State.HEADERS;
                    break;
                }
                case HEADERS:
                {
                    short version = controlFrameParser.getVersion();
                    int length = controlFrameParser.getLength() - getSynReplyDataLength(version);
                    if (headersBlockParser.parse(streamId, version, length, buffer))
                    {
                        byte flags = controlFrameParser.getFlags();
                        if (flags != 0 && flags != ReplyInfo.FLAG_CLOSE)
                            throw new IllegalArgumentException("Invalid flag " + flags + " for frame " + ControlFrameType.SYN_REPLY);

                        SynReplyFrame frame = new SynReplyFrame(version, flags, streamId, new Headers(headers, true));
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

    private int getSynReplyDataLength(short version)
    {
        switch (version)
        {
            case 2:
                return 6;
            case 3:
                return 4;
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
    }

    private enum State
    {
        STREAM_ID, STREAM_ID_BYTES, ADDITIONAL, ADDITIONAL_BYTES, HEADERS
    }

    private class SynReplyHeadersBlockParser extends HeadersBlockParser
    {
        public SynReplyHeadersBlockParser(CompressionFactory.Decompressor decompressor)
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
