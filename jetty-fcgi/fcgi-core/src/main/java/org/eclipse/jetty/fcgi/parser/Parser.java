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

import org.eclipse.jetty.fcgi.FCGI;
import org.eclipse.jetty.http.HttpField;

public abstract class Parser
{
    protected final HeaderParser headerParser = new HeaderParser();
    private State state = State.HEADER;
    private int padding;

    public void parse(ByteBuffer buffer)
    {
        while (true)
        {
            switch (state)
            {
                case HEADER:
                {
                    if (!headerParser.parse(buffer))
                        return;
                    state = State.CONTENT;
                    break;
                }
                case CONTENT:
                {
                    ContentParser contentParser = findContentParser(headerParser.getFrameType());
                    if (headerParser.getContentLength() == 0)
                    {
                        contentParser.noContent();
                    }
                    else
                    {
                        if (!contentParser.parse(buffer))
                            return;
                    }
                    padding = headerParser.getPaddingLength();
                    state = State.PADDING;
                    break;
                }
                case PADDING:
                {
                    if (buffer.remaining() >= padding)
                    {
                        buffer.position(buffer.position() + padding);
                        reset();
                        break;
                    }
                    else
                    {
                        padding -= buffer.remaining();
                        buffer.position(buffer.limit());
                        return;
                    }
                }
                default:
                {
                    throw new IllegalStateException();
                }
            }
        }
    }

    protected abstract ContentParser findContentParser(FCGI.FrameType frameType);

    private void reset()
    {
        headerParser.reset();
        state = State.HEADER;
        padding = 0;
    }

    public interface Listener
    {
        public void onHeader(int request, HttpField field);

        public void onHeaders(int request);

        public void onContent(int request, FCGI.StreamType stream, ByteBuffer buffer);

        public void onEnd(int request);

        public static class Adapter implements Listener
        {
            @Override
            public void onHeader(int request, HttpField field)
            {
            }

            @Override
            public void onHeaders(int request)
            {
            }

            @Override
            public void onContent(int request, FCGI.StreamType stream, ByteBuffer buffer)
            {
            }

            @Override
            public void onEnd(int request)
            {
            }
        }
    }

    private enum State
    {
        HEADER, CONTENT, PADDING
    }
}
