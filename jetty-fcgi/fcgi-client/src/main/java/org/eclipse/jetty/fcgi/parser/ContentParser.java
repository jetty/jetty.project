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

package org.eclipse.jetty.fcgi.parser;

import java.nio.ByteBuffer;

/**
 * <p>Parser for FastCGI frame content.</p>
 * <p>Depending on the frame type specified in the FastCGI frame header,
 * the FastCGI frame content has different formats and it is parsed by
 * different implementation of this abstract class.</p>
 * <p>There are these frame content types:</p>
 * <ul>
 *   <li>{@code BEGIN_REQUEST}, to signal the begin of the request</li>
 *   <li>{@code PARAMS}, key/value pairs</li>
 *   <li>{@code STDIN}, the request body, handled as a stream</li>
 *   <li>{@code STDOUT}, the response body, handled as a stream</li>
 *   <li>{@code STDERR}, the response error, handled as a stream</li>
 *   <li>{@code END_REQUEST}, to signal the end of the response</li>
 * </ul>
 *
 * @see Parser
 */
public abstract class ContentParser
{
    private final HeaderParser headerParser;

    protected ContentParser(HeaderParser headerParser)
    {
        this.headerParser = headerParser;
    }

    /**
     * <p>Parses the bytes in the given {@code buffer} as FastCGI frame content bytes.</p>
     *
     * @param buffer the bytes to parse
     * @return the result of the parsing
     */
    public abstract Result parse(ByteBuffer buffer);

    /**
     * <p>Invoked by the {@link Parser} when the frame content length is zero.</p>
     *
     * @return whether the parsing should stop
     */
    public boolean noContent()
    {
        throw new IllegalStateException();
    }

    protected int getRequest()
    {
        return headerParser.getRequest();
    }

    protected int getContentLength()
    {
        return headerParser.getContentLength();
    }

    /**
     * <p>The result of the frame content parsing.</p>
     */
    public enum Result
    {
        /**
         * <p>Not enough bytes have been provided to the parser
         * with a call to {@link ContentParser#parse(ByteBuffer)}.</p>
         */
        PENDING,
        /**
         * <p>The frame content has been parsed, but the application
         * signalled that it wants to process the content asynchronously.</p>
         */
        ASYNC,
        /**
         * <p>The frame content parsing is complete,
         * and the parser can now parse the padding bytes.</p>
         */
        COMPLETE
    }
}
