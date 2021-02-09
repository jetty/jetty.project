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

package org.eclipse.jetty.fcgi.server;

import java.nio.ByteBuffer;

import org.eclipse.jetty.fcgi.generator.Flusher;
import org.eclipse.jetty.fcgi.generator.Generator;
import org.eclipse.jetty.fcgi.generator.ServerGenerator;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.server.HttpTransport;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class HttpTransportOverFCGI implements HttpTransport
{
    private static final Logger LOG = Log.getLogger(HttpTransportOverFCGI.class);
    private final ServerGenerator generator;
    private final Flusher flusher;
    private final int request;
    private volatile boolean shutdown;
    private volatile boolean aborted;

    public HttpTransportOverFCGI(ByteBufferPool byteBufferPool, Flusher flusher, int request, boolean sendStatus200)
    {
        this.generator = new ServerGenerator(byteBufferPool, sendStatus200);
        this.flusher = flusher;
        this.request = request;
    }

    @Override
    public boolean isOptimizedForDirectBuffers()
    {
        return false;
    }

    @Override
    public void send(MetaData.Response info, boolean head, ByteBuffer content, boolean lastContent, Callback callback)
    {
        if (info != null)
            commit(info, head, content, lastContent, callback);
        else
        {
            if (head)
            {
                if (lastContent)
                {
                    Generator.Result result = generateResponseContent(BufferUtil.EMPTY_BUFFER, true, callback);
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
                Generator.Result result = generateResponseContent(content, lastContent, callback);
                flusher.flush(result);
            }

            if (lastContent && shutdown)
                flusher.shutdown();
        }
    }

    @Override
    public boolean isPushSupported()
    {
        return false;
    }

    @Override
    public void push(org.eclipse.jetty.http.MetaData.Request request)
    {
        // LOG.debug("ignore push in {}",this);
    }

    private void commit(MetaData.Response info, boolean head, ByteBuffer content, boolean lastContent, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("commit {} {} l={}", this, info, lastContent);
        boolean shutdown = this.shutdown = info.getFields().contains(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE.asString());

        if (head)
        {
            if (lastContent)
            {
                Generator.Result headersResult = generateResponseHeaders(info, Callback.NOOP);
                Generator.Result contentResult = generateResponseContent(BufferUtil.EMPTY_BUFFER, true, callback);
                flusher.flush(headersResult, contentResult);
            }
            else
            {
                Generator.Result headersResult = generateResponseHeaders(info, callback);
                flusher.flush(headersResult);
            }
        }
        else
        {
            Generator.Result headersResult = generateResponseHeaders(info, Callback.NOOP);
            Generator.Result contentResult = generateResponseContent(content, lastContent, callback);
            flusher.flush(headersResult, contentResult);
        }

        if (lastContent && shutdown)
            flusher.shutdown();
    }

    protected Generator.Result generateResponseHeaders(MetaData.Response info, Callback callback)
    {
        return generator.generateResponseHeaders(request, info.getStatus(), info.getReason(), info.getFields(), callback);
    }

    protected Generator.Result generateResponseContent(ByteBuffer buffer, boolean lastContent, Callback callback)
    {
        return generator.generateResponseContent(request, buffer, lastContent, aborted, callback);
    }

    @Override
    public void abort(Throwable failure)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("abort {} {}", this, failure);
        aborted = true;
        flusher.shutdown();
    }

    @Override
    public void onCompleted()
    {
    }
}
