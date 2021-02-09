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

package org.eclipse.jetty.websocket.common.io.http;

import java.nio.ByteBuffer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.Utf8LineParser;

/**
 * Responsible for reading UTF8 Response Header lines and parsing them into a provided UpgradeResponse object.
 */
public class HttpResponseHeaderParser
{
    @SuppressWarnings("serial")
    public static class ParseException extends RuntimeException
    {
        public ParseException(String message)
        {
            super(message);
        }

        public ParseException(String message, Throwable cause)
        {
            super(message, cause);
        }
    }

    private enum State
    {
        STATUS_LINE,
        HEADER,
        END
    }

    private static final Pattern PAT_HEADER = Pattern.compile("([^:]+):\\s*(.*)");
    private static final Pattern PAT_STATUS_LINE = Pattern.compile("^HTTP/1.[01]\\s+(\\d+)\\s+(.*)", Pattern.CASE_INSENSITIVE);

    private final HttpResponseHeaderParseListener listener;
    private final Utf8LineParser lineParser;
    private State state;

    public HttpResponseHeaderParser(HttpResponseHeaderParseListener listener)
    {
        this.listener = listener;
        this.lineParser = new Utf8LineParser();
        this.state = State.STATUS_LINE;
    }

    public boolean isDone()
    {
        return (state == State.END);
    }

    public HttpResponseHeaderParseListener parse(ByteBuffer buf) throws ParseException
    {
        while (!isDone() && (buf.remaining() > 0))
        {
            String line = lineParser.parse(buf);
            if (line != null)
            {
                if (parseHeader(line))
                {
                    // Now finished with parsing the entire response header
                    // Save the remaining bytes for WebSocket to process.

                    ByteBuffer copy = ByteBuffer.allocate(buf.remaining());
                    BufferUtil.put(buf, copy);
                    BufferUtil.flipToFlush(copy, 0);
                    this.listener.setRemainingBuffer(copy);
                    return listener;
                }
            }
        }
        return null;
    }

    private boolean parseHeader(String line) throws ParseException
    {
        switch (state)
        {
            case STATUS_LINE:
            {
                Matcher mat = PAT_STATUS_LINE.matcher(line);
                if (!mat.matches())
                {
                    throw new ParseException("Unexpected HTTP response status line [" + line + "]");
                }

                try
                {
                    listener.setStatusCode(Integer.parseInt(mat.group(1)));
                }
                catch (NumberFormatException e)
                {
                    throw new ParseException("Unexpected HTTP response status code", e);
                }
                listener.setStatusReason(mat.group(2));
                state = State.HEADER;
                break;
            }
            case HEADER:
            {
                if (StringUtil.isBlank(line))
                {
                    state = State.END;
                    return parseHeader(line);
                }

                Matcher header = PAT_HEADER.matcher(line);
                if (header.matches())
                {
                    String headerName = header.group(1);
                    String headerValue = header.group(2);
                    // do need to split header/value if comma delimited?
                    listener.addHeader(headerName, headerValue);
                }
                break;
            }
            case END:
                state = State.STATUS_LINE;
                return true;
        }
        return false;
    }
}
