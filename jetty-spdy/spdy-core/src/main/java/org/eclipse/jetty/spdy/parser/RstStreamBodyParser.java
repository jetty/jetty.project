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

import org.eclipse.jetty.spdy.frames.RstStreamFrame;

public class RstStreamBodyParser extends ControlFrameBodyParser
{
    private final ControlFrameParser controlFrameParser;
    private State state = State.STREAM_ID;
    private int cursor;
    private int streamId;
    private int statusCode;

    public RstStreamBodyParser(ControlFrameParser controlFrameParser)
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
                        state = State.STATUS_CODE;
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
                        state = State.STATUS_CODE;
                    }
                    break;
                }
                case STATUS_CODE:
                {
                    if (buffer.remaining() >= 4)
                    {
                        statusCode = buffer.getInt();
                        onRstStream();
                        return true;
                    }
                    else
                    {
                        state = State.STATUS_CODE_BYTES;
                        cursor = 4;
                    }
                    break;
                }
                case STATUS_CODE_BYTES:
                {
                    byte currByte = buffer.get();
                    --cursor;
                    statusCode += (currByte & 0xFF) << 8 * cursor;
                    if (cursor == 0)
                    {
                        onRstStream();
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

    private void onRstStream()
    {
        // TODO: check that statusCode is not 0
        RstStreamFrame frame = new RstStreamFrame(controlFrameParser.getVersion(), streamId, statusCode);
        controlFrameParser.onControlFrame(frame);
        reset();
    }

    private void reset()
    {
        state = State.STREAM_ID;
        cursor = 0;
        streamId = 0;
        statusCode = 0;
    }

    private enum State
    {
        STREAM_ID, STREAM_ID_BYTES, STATUS_CODE, STATUS_CODE_BYTES
    }
}
