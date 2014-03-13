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

package org.eclipse.jetty.fcgi.server;

import java.nio.ByteBuffer;

import org.eclipse.jetty.fcgi.generator.Flusher;
import org.eclipse.jetty.fcgi.generator.Generator;
import org.eclipse.jetty.fcgi.generator.ServerGenerator;
import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.server.HttpTransport;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;

public class HttpTransportOverFCGI implements HttpTransport
{
    private final ServerGenerator generator;
    private final Flusher flusher;
    private final int request;
    private volatile boolean head;

    public HttpTransportOverFCGI(ByteBufferPool byteBufferPool, Flusher flusher, int request)
    {
        this.generator = new ServerGenerator(byteBufferPool);
        this.flusher = flusher;
        this.request = request;
    }

    @Override
    public void send(HttpGenerator.ResponseInfo info, ByteBuffer content, boolean lastContent, Callback callback)
    {
        boolean head = this.head = info.isHead();
        if (head)
        {
            if (lastContent)
            {
                Generator.Result headersResult = generator.generateResponseHeaders(request, info.getStatus(), info.getReason(),
                        info.getHttpFields(), new Callback.Adapter());
                Generator.Result contentResult = generator.generateResponseContent(request, BufferUtil.EMPTY_BUFFER, lastContent, callback);
                flusher.flush(headersResult, contentResult);
            }
            else
            {
                Generator.Result headersResult = generator.generateResponseHeaders(request, info.getStatus(), info.getReason(),
                        info.getHttpFields(), callback);
                flusher.flush(headersResult);
            }
        }
        else
        {
            Generator.Result headersResult = generator.generateResponseHeaders(request, info.getStatus(), info.getReason(),
                    info.getHttpFields(), new Callback.Adapter());
            Generator.Result contentResult = generator.generateResponseContent(request, content, lastContent, callback);
            flusher.flush(headersResult, contentResult);
        }
    }

    @Override
    public void send(ByteBuffer content, boolean lastContent, Callback callback)
    {
        if (head)
        {
            if (lastContent)
            {
                Generator.Result result = generator.generateResponseContent(request, BufferUtil.EMPTY_BUFFER, lastContent, callback);
                flusher.flush(result);
            }
            else
            {
                // Skip content generation
                callback.succeeded();
            }
        }
        else
        {
            Generator.Result result = generator.generateResponseContent(request, content, lastContent, callback);
            flusher.flush(result);
        }
    }

    @Override
    public void completed()
    {
    }
}
