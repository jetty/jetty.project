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
import java.util.EnumMap;

import org.eclipse.jetty.spdy.CompressionFactory;
import org.eclipse.jetty.spdy.frames.ControlFrame;
import org.eclipse.jetty.spdy.frames.ControlFrameType;

public abstract class ControlFrameParser
{
    private final EnumMap<ControlFrameType, ControlFrameBodyParser> parsers = new EnumMap<>(ControlFrameType.class);
    private final ControlFrameBodyParser unknownParser = new UnknownControlFrameBodyParser(this);
    private State state = State.VERSION;
    private int cursor;
    private short version;
    private short type;
    private byte flags;
    private int length;
    private ControlFrameBodyParser parser;

    public ControlFrameParser(CompressionFactory.Decompressor decompressor)
    {
        parsers.put(ControlFrameType.SYN_STREAM, new SynStreamBodyParser(decompressor, this));
        parsers.put(ControlFrameType.SYN_REPLY, new SynReplyBodyParser(decompressor, this));
        parsers.put(ControlFrameType.RST_STREAM, new RstStreamBodyParser(this));
        parsers.put(ControlFrameType.SETTINGS, new SettingsBodyParser(this));
        parsers.put(ControlFrameType.NOOP, new NoOpBodyParser(this));
        parsers.put(ControlFrameType.PING, new PingBodyParser(this));
        parsers.put(ControlFrameType.GO_AWAY, new GoAwayBodyParser(this));
        parsers.put(ControlFrameType.HEADERS, new HeadersBodyParser(decompressor, this));
        parsers.put(ControlFrameType.WINDOW_UPDATE, new WindowUpdateBodyParser(this));
    }

    public short getVersion()
    {
        return version;
    }

    public byte getFlags()
    {
        return flags;
    }

    public int getLength()
    {
        return length;
    }

    public boolean parse(ByteBuffer buffer)
    {
        while (buffer.hasRemaining())
        {
            switch (state)
            {
                case VERSION:
                {
                    if (buffer.remaining() >= 2)
                    {
                        version = (short)(buffer.getShort() & 0x7F_FF);
                        state = State.TYPE;
                    }
                    else
                    {
                        state = State.VERSION_BYTES;
                        cursor = 2;
                    }
                    break;
                }
                case VERSION_BYTES:
                {
                    byte currByte = buffer.get();
                    --cursor;
                    version += (currByte & 0xFF) << 8 * cursor;
                    if (cursor == 0)
                    {
                        version &= 0x7F_FF;
                        state = State.TYPE;
                    }
                    break;
                }
                case TYPE:
                {
                    if (buffer.remaining() >= 2)
                    {
                        type = buffer.getShort();
                        state = State.FLAGS;
                    }
                    else
                    {
                        state = State.TYPE_BYTES;
                        cursor = 2;
                    }
                    break;
                }
                case TYPE_BYTES:
                {
                    byte currByte = buffer.get();
                    --cursor;
                    type += (currByte & 0xFF) << 8 * cursor;
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

                    ControlFrameType controlFrameType = ControlFrameType.from(type);

                    // SPEC v3, 2.2.1: unrecognized control frames must be ignored
                    if (controlFrameType == null)
                        parser = unknownParser;
                    else
                        parser = parsers.get(controlFrameType);

                    state = State.BODY;

                    // We have to let it fall through the next switch:
                    // the NOOP frame has no body and we cannot break
                    // because the buffer may be consumed and we will
                    // never enter the BODY case.
                }
                case BODY:
                {
                    if (parser.parse(buffer))
                    {
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

    private void reset()
    {
        state = State.VERSION;
        cursor = 0;
        version = 0;
        type = 0;
        flags = 0;
        length = 0;
        parser = null;
    }

    protected abstract void onControlFrame(ControlFrame frame);

    private enum State
    {
        VERSION, VERSION_BYTES, TYPE, TYPE_BYTES, FLAGS, LENGTH, BODY
    }
}
