//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.fcgi.parser;

import java.nio.ByteBuffer;

/**
 * <p>Parser for the END_REQUEST frame content.</p>
 * <pre>
 * struct end_request_body {
 *     uint applicationStatus;
 *     ubyte protocolStatus;
 *     ubyte[3] reserved;
 * }
 * </pre>
 */
public class EndRequestContentParser extends ContentParser
{
    private final Parser.Listener listener;
    private State state = State.APPLICATION;
    private int cursor;
    private int application;
    private int protocol;

    public EndRequestContentParser(HeaderParser headerParser, Parser.Listener listener)
    {
        super(headerParser);
        this.listener = listener;
    }

    @Override
    public Result parse(ByteBuffer buffer)
    {
        while (buffer.hasRemaining())
        {
            switch (state)
            {
                case APPLICATION:
                {
                    if (buffer.remaining() >= 4)
                    {
                        application = buffer.getInt();
                        state = State.PROTOCOL;
                    }
                    else
                    {
                        state = State.APPLICATION_BYTES;
                        cursor = 0;
                    }
                    break;
                }
                case APPLICATION_BYTES:
                {
                    int quarterInt = buffer.get() & 0xFF;
                    application = (application << 8) + quarterInt;
                    if (++cursor == 4)
                        state = State.PROTOCOL;
                    break;
                }
                case PROTOCOL:
                {
                    protocol = buffer.get() & 0xFF;
                    state = State.RESERVED;
                    break;
                }
                case RESERVED:
                {
                    if (buffer.remaining() >= 3)
                    {
                        buffer.position(buffer.position() + 3);
                        onEnd();
                        reset();
                        return Result.COMPLETE;
                    }
                    else
                    {
                        state = State.RESERVED_BYTES;
                        cursor = 0;
                        break;
                    }
                }
                case RESERVED_BYTES:
                {
                    buffer.get();
                    if (++cursor == 3)
                    {
                        onEnd();
                        reset();
                        return Result.COMPLETE;
                    }
                    break;
                }
                default:
                {
                    throw new IllegalStateException();
                }
            }
        }
        return Result.PENDING;
    }

    private void onEnd()
    {
        if (application != 0)
            listener.onFailure(getRequest(), new Exception("FastCGI application returned code " + application));
        else if (protocol != 0)
            listener.onFailure(getRequest(), new Exception("FastCGI server returned code " + protocol));
        else
            listener.onEnd(getRequest());
    }

    private void reset()
    {
        state = State.APPLICATION;
        cursor = 0;
        application = 0;
        protocol = 0;
    }

    private enum State
    {
        APPLICATION, APPLICATION_BYTES, PROTOCOL, RESERVED, RESERVED_BYTES
    }
}
