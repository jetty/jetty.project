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

import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.frames.GoAwayFrame;

public class GoAwayBodyParser extends ControlFrameBodyParser
{
    private final ControlFrameParser controlFrameParser;
    private State state = State.LAST_GOOD_STREAM_ID;
    private int cursor;
    private int lastStreamId;
    private int statusCode;

    public GoAwayBodyParser(ControlFrameParser controlFrameParser)
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
                case LAST_GOOD_STREAM_ID:
                {
                    if (buffer.remaining() >= 4)
                    {
                        lastStreamId = buffer.getInt() & 0x7F_FF_FF_FF;
                        switch (controlFrameParser.getVersion())
                        {
                            case SPDY.V2:
                            {
                                onGoAway();
                                return true;
                            }
                            case SPDY.V3:
                            {
                                state = State.STATUS_CODE;
                                break;
                            }
                            default:
                            {
                                throw new IllegalStateException();
                            }
                        }
                    }
                    else
                    {
                        state = State.LAST_GOOD_STREAM_ID_BYTES;
                        cursor = 4;
                    }
                    break;
                }
                case LAST_GOOD_STREAM_ID_BYTES:
                {
                    byte currByte = buffer.get();
                    --cursor;
                    lastStreamId += (currByte & 0xFF) << 8 * cursor;
                    if (cursor == 0)
                    {
                        lastStreamId &= 0x7F_FF_FF_FF;
                        switch (controlFrameParser.getVersion())
                        {
                            case SPDY.V2:
                            {
                                onGoAway();
                                return true;
                            }
                            case SPDY.V3:
                            {
                                state = State.STATUS_CODE;
                                break;
                            }
                            default:
                            {
                                throw new IllegalStateException();
                            }
                        }
                    }
                    break;
                }
                case STATUS_CODE:
                {
                    if (buffer.remaining() >= 4)
                    {
                        statusCode = buffer.getInt();
                        onGoAway();
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
                        onGoAway();
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

    private void onGoAway()
    {
        GoAwayFrame frame = new GoAwayFrame(controlFrameParser.getVersion(), lastStreamId, statusCode);
        controlFrameParser.onControlFrame(frame);
        reset();
    }

    private void reset()
    {
        state = State.LAST_GOOD_STREAM_ID;
        cursor = 0;
        lastStreamId = 0;
        statusCode = 0;
    }

    private enum State
    {
        LAST_GOOD_STREAM_ID, LAST_GOOD_STREAM_ID_BYTES, STATUS_CODE, STATUS_CODE_BYTES
    }
}
