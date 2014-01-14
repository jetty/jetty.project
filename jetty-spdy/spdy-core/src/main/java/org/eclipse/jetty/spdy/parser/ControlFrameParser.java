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
    private ControlFrameBodyParser bodyParser;
    private int bytesToSkip = 0;

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
        parsers.put(ControlFrameType.CREDENTIAL, new CredentialBodyParser(this));
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

    public void skip(int bytesToSkip)
    {
        state = State.SKIP;
        this.bytesToSkip = bytesToSkip;
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
                        bodyParser = unknownParser;
                    else
                        bodyParser = parsers.get(controlFrameType);

                    state = State.BODY;

                    // We have to let it fall through the next switch:
                    // the NOOP frame has no body and we cannot break
                    // because the buffer may be consumed and we will
                    // never enter the BODY case.
                }
                case BODY:
                {
                    if (bodyParser.parse(buffer))
                    {
                        reset();
                        return true;
                    }
                    break;
                }
                case SKIP:
                {
                    int remaining = buffer.remaining();
                    if (remaining >= bytesToSkip)
                    {
                        buffer.position(buffer.position() + bytesToSkip);
                        reset();
                        return true;
                    }
                    else
                    {
                        buffer.position(buffer.limit());
                        bytesToSkip = bytesToSkip - remaining;
                        return false;
                    }
                }
                default:
                {
                    throw new IllegalStateException();
                }
            }
        }
        return false;
    }

    void reset()
    {
        state = State.VERSION;
        cursor = 0;
        version = 0;
        type = 0;
        flags = 0;
        length = 0;
        bodyParser = null;
        bytesToSkip = 0;
    }

    protected abstract void onControlFrame(ControlFrame frame);

    private enum State
    {
        VERSION, VERSION_BYTES, TYPE, TYPE_BYTES, FLAGS, LENGTH, BODY, SKIP
    }
}
