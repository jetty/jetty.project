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

package org.eclipse.jetty.http3.server.internal;

import org.eclipse.jetty.http3.frames.DataFrame;
import org.eclipse.jetty.http3.internal.HTTP3Stream;
import org.eclipse.jetty.http3.internal.parser.MessageParser;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpInput;
import org.eclipse.jetty.server.HttpTransport;

public class HttpChannelOverHTTP3 extends HttpChannel
{
    private final HTTP3Stream stream;
    private final ServerHTTP3StreamConnection connection;
    private HttpInput.Content content;

    public HttpChannelOverHTTP3(Connector connector, HttpConfiguration configuration, EndPoint endPoint, HttpTransport transport, HTTP3Stream stream, ServerHTTP3StreamConnection connection)
    {
        super(connector, configuration, endPoint, transport);
        this.stream = stream;
        this.connection = connection;
    }

    @Override
    public boolean needContent()
    {
        if (content != null)
            return true;

        MessageParser.Result result = connection.parseAndFill();
        if (result == MessageParser.Result.FRAME)
        {
            DataFrame dataFrame = connection.pollContent();
            content = new HttpInput.Content(dataFrame.getByteBuffer())
            {
                @Override
                public boolean isEof()
                {
                    return dataFrame.isLast();
                }
            };
            return true;
        }
        else
        {
            stream.demand();
            return false;
        }
    }

    @Override
    public HttpInput.Content produceContent()
    {
        HttpInput.Content result = content;
        if (result != null && !result.isSpecial())
            content = result.isEof() ? new HttpInput.EofContent() : null;
        return result;
    }

    @Override
    public boolean failAllContent(Throwable failure)
    {
        return false;
    }

    @Override
    public boolean failed(Throwable failure)
    {
        return false;
    }

    @Override
    protected boolean eof()
    {
        return false;
    }
}
