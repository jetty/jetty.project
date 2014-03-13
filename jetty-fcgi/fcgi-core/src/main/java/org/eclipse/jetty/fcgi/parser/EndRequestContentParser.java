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

package org.eclipse.jetty.fcgi.parser;

import java.nio.ByteBuffer;

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
    public boolean parse(ByteBuffer buffer)
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
                        return true;
                    }
                    else
                    {
                        state = State.APPLICATION_BYTES;
                        cursor = 0;
                        break;
                    }
                }
                case RESERVED_BYTES:
                {
                    buffer.get();
                    if (++cursor == 0)
                    {
                        onEnd();
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

    private void onEnd()
    {
        // TODO: if protocol != 0, invoke an error callback
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
