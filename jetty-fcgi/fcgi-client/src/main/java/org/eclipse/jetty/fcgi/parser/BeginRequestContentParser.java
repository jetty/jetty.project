//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.fcgi.parser;

import java.nio.ByteBuffer;

import org.eclipse.jetty.fcgi.FCGI;

/**
 * <p>Parser for the BEGIN_REQUEST frame body.</p>
 * <pre>
 * struct begin_request_body {
 *     ushort role;
 *     ubyte flags;
 *     ubyte[5] reserved;
 * }
 * </pre>
 */
public class BeginRequestContentParser extends ContentParser
{
    private final ServerParser.Listener listener;
    private State state = State.ROLE;
    private int cursor;
    private int role;
    private int flags;

    public BeginRequestContentParser(HeaderParser headerParser, ServerParser.Listener listener)
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
                case ROLE:
                {
                    if (buffer.remaining() >= 2)
                    {
                        role = buffer.getShort();
                        state = State.FLAGS;
                    }
                    else
                    {
                        state = State.ROLE_BYTES;
                        cursor = 0;
                    }
                    break;
                }
                case ROLE_BYTES:
                {
                    int halfShort = buffer.get() & 0xFF;
                    role = (role << 8) + halfShort;
                    if (++cursor == 2)
                        state = State.FLAGS;
                    break;
                }
                case FLAGS:
                {
                    flags = buffer.get() & 0xFF;
                    state = State.RESERVED;
                    break;
                }
                case RESERVED:
                {
                    if (buffer.remaining() >= 5)
                    {
                        buffer.position(buffer.position() + 5);
                        onStart();
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
                    if (++cursor == 5)
                    {
                        onStart();
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

    private void onStart()
    {
        listener.onStart(getRequest(), FCGI.Role.from(role), flags);
    }

    private void reset()
    {
        state = State.ROLE;
        cursor = 0;
        role = 0;
        flags = 0;
    }

    private enum State
    {
        ROLE, ROLE_BYTES, FLAGS, RESERVED, RESERVED_BYTES
    }
}
