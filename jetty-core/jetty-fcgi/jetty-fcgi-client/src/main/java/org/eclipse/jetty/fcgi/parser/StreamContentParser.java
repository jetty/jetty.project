//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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

import org.eclipse.jetty.fcgi.FCGI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A stream content parser parses frame bodies of type STDIN, STDOUT and STDERR.</p>
 * <p>STDOUT frame bodies are handled specially by {@link ResponseContentParser}.
 */
public class StreamContentParser extends ContentParser
{
    private static final Logger LOG = LoggerFactory.getLogger(StreamContentParser.class);

    private final FCGI.StreamType streamType;
    private final Parser.Listener listener;
    private State state = State.LENGTH;
    private int contentLength;

    public StreamContentParser(HeaderParser headerParser, FCGI.StreamType streamType, Parser.Listener listener)
    {
        super(headerParser);
        this.streamType = streamType;
        this.listener = listener;
    }

    @Override
    public Result parse(ByteBuffer buffer)
    {
        while (buffer.hasRemaining())
        {
            switch (state)
            {
                case LENGTH:
                {
                    contentLength = getContentLength();
                    state = State.CONTENT;
                    break;
                }
                case CONTENT:
                {
                    int length = Math.min(contentLength, buffer.remaining());
                    ByteBuffer slice = buffer.slice(buffer.position(), length);
                    // Only parse the content of this FCGI frame.
                    boolean result = onContent(slice);
                    // Not all the content may have been parsed.
                    int consumed = length - slice.remaining();
                    buffer.position(buffer.position() + consumed);
                    contentLength -= consumed;
                    if (contentLength <= 0)
                        state = State.EOF;
                    if (result)
                        return Result.ASYNC;
                    break;
                }
                case EOF:
                {
                    state = State.LENGTH;
                    return Result.COMPLETE;
                }
                default:
                {
                    throw new IllegalStateException();
                }
            }
        }
        return Result.PENDING;
    }

    @Override
    public boolean noContent()
    {
        try
        {
            listener.onEnd(getRequest());
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Exception while invoking listener {}", listener, x);
        }
        return false;
    }

    protected boolean onContent(ByteBuffer buffer)
    {
        try
        {
            ByteBuffer content = buffer.asReadOnlyBuffer();
            buffer.position(buffer.limit());
            return listener.onContent(getRequest(), streamType, content);
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Exception while invoking listener {}", listener, x);
            return false;
        }
    }

    protected void end(int request)
    {
    }

    private enum State
    {
        LENGTH, CONTENT, EOF
    }
}
