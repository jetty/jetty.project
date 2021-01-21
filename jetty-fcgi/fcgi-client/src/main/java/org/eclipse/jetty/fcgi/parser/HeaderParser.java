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
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * <p>Parser for FastCGI frame headers.</p>
 * <pre>
 * struct frame_header {
 *     ubyte version;
 *     ubyte type;
 *     ushort requestId;
 *     ushort contentLength;
 *     ubyte paddingLength;
 *     ubyte reserved;
 * }
 * </pre>
 *
 * @see Parser
 */
public class HeaderParser
{
    private static final Logger LOG = Log.getLogger(Parser.class);

    private State state = State.VERSION;
    private int cursor;
    private int version;
    private int type;
    private int request;
    private int length;
    private int padding;

    /**
     * Parses the bytes in the given {@code buffer} as FastCGI header bytes
     *
     * @param buffer the bytes to parse
     * @return whether there were enough bytes for a FastCGI header
     */
    public boolean parse(ByteBuffer buffer)
    {
        while (buffer.hasRemaining())
        {
            switch (state)
            {
                case VERSION:
                {
                    version = buffer.get() & 0xFF;
                    state = State.TYPE;
                    break;
                }
                case TYPE:
                {
                    type = buffer.get() & 0xFF;
                    state = State.REQUEST;
                    break;
                }
                case REQUEST:
                {
                    if (buffer.remaining() >= 2)
                    {
                        request = buffer.getShort() & 0xFF_FF;
                        state = State.LENGTH;
                    }
                    else
                    {
                        state = State.REQUEST_BYTES;
                        cursor = 0;
                    }
                    break;
                }
                case REQUEST_BYTES:
                {
                    int halfShort = buffer.get() & 0xFF;
                    request = (request << 8) + halfShort;
                    if (++cursor == 2)
                        state = State.LENGTH;
                    break;
                }
                case LENGTH:
                {
                    if (buffer.remaining() >= 2)
                    {
                        length = buffer.getShort() & 0xFF_FF;
                        state = State.PADDING;
                    }
                    else
                    {
                        state = State.LENGTH_BYTES;
                        cursor = 0;
                    }
                    break;
                }
                case LENGTH_BYTES:
                {
                    int halfShort = buffer.get() & 0xFF;
                    length = (length << 8) + halfShort;
                    if (++cursor == 2)
                        state = State.PADDING;
                    break;
                }
                case PADDING:
                {
                    padding = buffer.get() & 0xFF;
                    state = State.RESERVED;
                    break;
                }
                case RESERVED:
                {
                    buffer.get();
                    if (LOG.isDebugEnabled())
                        LOG.debug("Parsed request {} header {} length={}", getRequest(), getFrameType(), getContentLength());
                    return true;
                }
                default:
                {
                    throw new IllegalStateException();
                }
            }
        }
        return false;
    }

    public FCGI.FrameType getFrameType()
    {
        return FCGI.FrameType.from(type);
    }

    public int getRequest()
    {
        return request;
    }

    public int getContentLength()
    {
        return length;
    }

    public int getPaddingLength()
    {
        return padding;
    }

    protected void reset()
    {
        state = State.VERSION;
        cursor = 0;
        version = 0;
        type = 0;
        request = 0;
        length = 0;
        padding = 0;
    }

    private enum State
    {
        VERSION, TYPE, REQUEST, REQUEST_BYTES, LENGTH, LENGTH_BYTES, PADDING, RESERVED
    }
}
