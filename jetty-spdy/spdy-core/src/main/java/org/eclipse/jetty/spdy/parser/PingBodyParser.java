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
