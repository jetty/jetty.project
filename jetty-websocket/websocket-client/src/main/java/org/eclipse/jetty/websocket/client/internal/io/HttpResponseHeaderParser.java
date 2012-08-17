//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.client.internal.io;

import java.nio.ByteBuffer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.Utf8LineParser;
import org.eclipse.jetty.websocket.api.UpgradeException;
import org.eclipse.jetty.websocket.api.UpgradeResponse;
import org.eclipse.jetty.websocket.client.internal.ClientUpgradeResponse;

/**
 * Responsible for reading UTF8 Response Header lines and parsing them into a provided UpgradeResponse object.
 */
public class HttpResponseHeaderParser
{
    private enum State
    {
        STATUS_LINE,
        HEADER,
        END
    }

    private static final Pattern PAT_HEADER = Pattern.compile("([^:]+):\\s*(.*)");
    private static final Pattern PAT_STATUS_LINE = Pattern.compile("^HTTP/1.[01]\\s+(\\d+)\\s+(.*)",Pattern.CASE_INSENSITIVE);

    private ClientUpgradeResponse response;
    private Utf8LineParser lineParser;
    private State state;

    public HttpResponseHeaderParser()
    {
        this.lineParser = new Utf8LineParser();
        this.state = State.STATUS_LINE;
    }

    public boolean isDone()
    {
        return (state == State.END);
    }

    public UpgradeResponse parse(ByteBuffer buf) throws UpgradeException
    {
        while (!isDone() && (buf.remaining() > 0))
        {
            String line = lineParser.parse(buf);
            if (line != null)
            {
                if (parseHeader(line))
                {
                    return this.response;
                }
            }
        }
        return null;
    }

    private boolean parseHeader(String line)
    {
        switch (state)
        {
            case STATUS_LINE:
            {
                this.response = new ClientUpgradeResponse();
                Matcher mat = PAT_STATUS_LINE.matcher(line);
                if (!mat.matches())
                {
                    throw new UpgradeException("Unexpected HTTP upgrade response status line [" + line + "]");
                }

                try
                {
                    response.setStatusCode(Integer.parseInt(mat.group(1)));
                }
                catch (NumberFormatException e)
                {
                    throw new UpgradeException("Unexpected HTTP upgrade response status code",e);
                }
                response.setStatusReason(mat.group(2));
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
                    response.addHeader(headerName,headerValue);
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
