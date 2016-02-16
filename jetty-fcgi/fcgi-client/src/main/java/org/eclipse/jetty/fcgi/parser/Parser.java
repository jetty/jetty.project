//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

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
 *
 * @see HeaderParser
 * @see ContentParser
 */
public abstract class Parser
{
    private static final Logger LOG = Log.getLogger(Parser.class);

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
                        contentParser.noContent();
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

        public void onHeaders(int request);

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
            public void onHeaders(int request)
            {
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
