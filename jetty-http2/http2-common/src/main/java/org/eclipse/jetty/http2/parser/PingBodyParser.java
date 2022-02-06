//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http2.parser;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.Flags;
import org.eclipse.jetty.http2.frames.PingFrame;

public class PingBodyParser extends BodyParser
{
    private State state = State.PREPARE;
    private int cursor;
    private byte[] payload;

    public PingBodyParser(HeaderParser headerParser, Parser.Listener listener)
    {
        super(headerParser, listener);
    }

    private void reset()
    {
        state = State.PREPARE;
        cursor = 0;
        payload = null;
    }

    @Override
    public boolean parse(ByteBuffer buffer)
    {
        while (buffer.hasRemaining())
        {
            switch (state)
            {
                case PREPARE:
                {
                    // SPEC: wrong streamId is treated as connection error.
                    if (getStreamId() != 0)
                        return connectionFailure(buffer, ErrorCode.PROTOCOL_ERROR.code, "invalid_ping_frame");
                    // SPEC: wrong body length is treated as connection error.
                    if (getBodyLength() != 8)
                        return connectionFailure(buffer, ErrorCode.FRAME_SIZE_ERROR.code, "invalid_ping_frame");
                    state = State.PAYLOAD;
                    break;
                }
                case PAYLOAD:
                {
                    payload = new byte[8];
                    if (buffer.remaining() >= 8)
                    {
                        buffer.get(payload);
                        return onPing(buffer, payload);
                    }
                    else
                    {
                        state = State.PAYLOAD_BYTES;
                        cursor = 8;
                    }
                    break;
                }
                case PAYLOAD_BYTES:
                {
                    payload[8 - cursor] = buffer.get();
                    --cursor;
                    if (cursor == 0)
                        return onPing(buffer, payload);
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

    private boolean onPing(ByteBuffer buffer, byte[] payload)
    {
        PingFrame frame = new PingFrame(payload, hasFlag(Flags.ACK));
        if (!rateControlOnEvent(frame))
            return connectionFailure(buffer, ErrorCode.ENHANCE_YOUR_CALM_ERROR.code, "invalid_ping_frame_rate");
        reset();
        notifyPing(frame);
        return true;
    }

    private enum State
    {
        PREPARE, PAYLOAD, PAYLOAD_BYTES
    }
}
