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

import org.eclipse.jetty.fcgi.FCGI;
import org.eclipse.jetty.http.HttpField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>The FastCGI protocol exchanges <em>frames</em>.</p>
 * <pre>
 * struct frame {
 *     ubyte version;
 *     ubyte type;
 *     ushort requestId;
 *     ushort contentLength;
 *     ubyte paddingLength;
 *     ubyte reserved;
 *     ubyte[] content;
 *     ubyte[] padding;
 * }
 * </pre>
 * <p>Depending on the {@code type}, the content may have a different format,
 * so there are specialized content parsers.</p>
 * <p>A typical exchange is:</p>
 * <pre>
 * BEGIN_REQUEST
 * PARAMS (length &gt; 0)
 * PARAMS (length == 0 to signal end of PARAMS frames)
 * [STDIN (length &gt; 0 in case of request content)]
 * STDIN (length == 0 to signal end of STDIN frames and end of request)
 * ...
 * STDOUT (length &gt; 0 with HTTP headers and HTTP content)
 * STDOUT (length == 0 to signal end of STDOUT frames)
 * [STDERR (length &gt; 0)]
 * [STDERR (length == 0 to signal end of STDERR frames)]
 * END_REQUEST
 * </pre>
 *
 * @see HeaderParser
 * @see ContentParser
 */
public abstract class Parser
{
    private static final Logger LOG = LoggerFactory.getLogger(Parser.class);

    protected final HeaderParser headerParser = new HeaderParser();
    private State state = State.HEADER;
    private int padding;

    /**
     * @param buffer the bytes to parse
     * @return true if the caller should stop parsing, false if the caller should continue parsing
     */
    public boolean parse(ByteBuffer buffer)
    {
        while (true)
        {
            switch (state)
            {
                case HEADER:
                {
                    if (!headerParser.parse(buffer))
                        return false;
                    state = State.CONTENT;
                    break;
                }
                case CONTENT:
                {
                    ContentParser contentParser = findContentParser(headerParser.getFrameType());
                    if (headerParser.getContentLength() == 0)
                    {
                        padding = headerParser.getPaddingLength();
                        state = State.PADDING;
                        if (contentParser.noContent())
                            return true;
                    }
                    else
                    {
                        ContentParser.Result result = contentParser.parse(buffer);
                        if (LOG.isDebugEnabled())
                            LOG.debug("Parsed request {} content {} result={}", headerParser.getRequest(), headerParser.getFrameType(), result);

                        if (result == ContentParser.Result.PENDING)
                        {
                            // Not enough data, signal to read/parse more.
                            return false;
                        }
                        if (result == ContentParser.Result.ASYNC)
                        {
                            // The content will be processed asynchronously, signal to stop
                            // parsing; the async operation will eventually resume parsing.
                            return true;
                        }
                        padding = headerParser.getPaddingLength();
                        state = State.PADDING;
                    }
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
                        return false;
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

        /**
         * @param request the request id
         * @return true to signal to the parser to stop parsing, false to continue parsing
         */
        public boolean onHeaders(int request);

        /**
         * @param request the request id
         * @param stream the stream type
         * @param buffer the content bytes
         * @return true to signal to the parser to stop parsing, false to continue parsing
         * @see Parser#parse(java.nio.ByteBuffer)
         */
        public boolean onContent(int request, FCGI.StreamType stream, ByteBuffer buffer);

        public void onEnd(int request);

        public void onFailure(int request, Throwable failure);

        public static class Adapter implements Listener
        {
            @Override
            public void onHeader(int request, HttpField field)
            {
            }

            @Override
            public boolean onHeaders(int request)
            {
                return false;
            }

            @Override
            public boolean onContent(int request, FCGI.StreamType stream, ByteBuffer buffer)
            {
                return false;
            }

            @Override
            public void onEnd(int request)
            {
            }

            @Override
            public void onFailure(int request, Throwable failure)
            {
            }
        }
    }

    private enum State
    {
        HEADER, CONTENT, PADDING
    }
}
