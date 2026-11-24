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

import java.io.EOFException;
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
    private final Listener listener;
    private State state = State.INITIAL;
    private int padding;
    private boolean endRequest;

    protected Parser(Listener listener)
    {
        this.listener = listener;
    }

    /**
     * @param buffer the bytes to parse
     * @return true if the caller should stop parsing, false if the caller should continue parsing
     */
    public boolean parse(ByteBuffer buffer)
    {
        try
        {
            while (true)
            {
                switch (state)
                {
                    case INITIAL ->
                    {
                        if (!buffer.hasRemaining())
                            return false;
                        state = State.HEADER;
                    }
                    case HEADER ->
                    {
                        if (!headerParser.parse(buffer))
                            return false;
                        state = State.CONTENT;
                    }
                    case CONTENT ->
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
                            // END_REQUEST has been notified, so parsing must
                            // stop after skipping the padding in the buffer.
                            endRequest = headerParser.getFrameType() == FCGI.FrameType.END_REQUEST;
                        }
                    }
                    case PADDING ->
                    {
                        int length = Math.min(padding, buffer.remaining());
                        buffer.position(buffer.position() + length);
                        padding -= length;
                        // Stop parsing only in the same call that notified
                        // END_REQUEST, as no event is notified for the padding
                        // or for other frame types.
                        // Bytes remaining after the end of the request are left
                        // in the buffer for the caller to handle, while padding
                        // bytes not received yet are skipped by the next call.
                        boolean stop = endRequest;
                        endRequest = false;
                        if (padding == 0)
                            reset();
                        if (stop)
                            return true;
                        if (padding > 0)
                            return false;
                    }
                    default -> throw new IllegalStateException();
                }
            }
        }
        catch (Throwable x)
        {
            buffer.position(buffer.limit());
            listener.onFailure(headerParser.getRequest(), x);
            return true;
        }
    }

    protected abstract ContentParser findContentParser(FCGI.FrameType frameType);

    public boolean eof()
    {
        if (state == State.INITIAL)
            return false;
        Throwable failure = new EOFException();
        listener.onFailure(headerParser.getRequest(), failure);
        return true;
    }

    private void reset()
    {
        headerParser.reset();
        state = State.INITIAL;
        padding = 0;
        endRequest = false;
    }

    public interface Listener
    {
        public default void onHeader(int request, HttpField field)
        {
        }

        /**
         * @param request the request id
         * @return true to signal to the parser to stop parsing, false to continue parsing
         */
        public default boolean onHeaders(int request)
        {
            return false;
        }

        /**
         * @param request the request id
         * @param stream the stream type
         * @param buffer the content bytes
         * @return true to signal to the parser to stop parsing, false to continue parsing
         * @see Parser#parse(java.nio.ByteBuffer)
         */
        public default boolean onContent(int request, FCGI.StreamType stream, ByteBuffer buffer)
        {
            return false;
        }

        public default boolean onEnd(int request)
        {
            return false;
        }

        public default void onFailure(int request, Throwable failure)
        {
        }
    }

    private enum State
    {
        INITIAL, HEADER, CONTENT, PADDING
    }
}
