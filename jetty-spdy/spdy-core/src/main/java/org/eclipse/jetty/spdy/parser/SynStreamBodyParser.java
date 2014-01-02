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

import org.eclipse.jetty.spdy.CompressionFactory;
import org.eclipse.jetty.spdy.PushSynInfo;
import org.eclipse.jetty.spdy.StreamException;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.api.StreamStatus;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.frames.ControlFrameType;
import org.eclipse.jetty.spdy.frames.SynStreamFrame;
import org.eclipse.jetty.util.Fields;

public class SynStreamBodyParser extends ControlFrameBodyParser
{
    private final Fields headers = new Fields();
    private final ControlFrameParser controlFrameParser;
    private final HeadersBlockParser headersBlockParser;
    private State state = State.STREAM_ID;
    private int cursor;
    private int streamId;
    private int associatedStreamId;
    private byte priority;
    private short slot;

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
                    try
                    {
                        checkVersion(controlFrameParser.getVersion(), streamId);
                    }
                    catch (StreamException e)
                    {
                        // We've already read 4 bytes of the streamId which are part of controlFrameParser.getLength
                        // so we need to substract those from the bytesToSkip.
                        int bytesToSkip = controlFrameParser.getLength() - 4;
                        int remaining = buffer.remaining();
                        if (remaining >= bytesToSkip)
                        {
                            buffer.position(buffer.position() + bytesToSkip);
                            controlFrameParser.reset();
                            reset();
                        }
                        else
                        {
                            int bytesToSkipInNextBuffer = bytesToSkip - remaining;
                            buffer.position(buffer.limit());
                            controlFrameParser.skip(bytesToSkipInNextBuffer);
                            reset();
                        }
                        throw e;
                    }
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
                        slot = (short)(currByte & 0xFF);
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
                        if (flags > (SynInfo.FLAG_CLOSE | PushSynInfo.FLAG_UNIDIRECTIONAL))
                            throw new IllegalArgumentException("Invalid flag " + flags + " for frame " +
                                    ControlFrameType.SYN_STREAM);

                        SynStreamFrame frame = new SynStreamFrame(version, flags, streamId, associatedStreamId,
                                priority, slot, new Fields(headers, false));
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
