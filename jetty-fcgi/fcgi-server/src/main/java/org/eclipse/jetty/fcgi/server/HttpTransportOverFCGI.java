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

package org.eclipse.jetty.fcgi.server;

import java.nio.ByteBuffer;

import org.eclipse.jetty.fcgi.generator.Flusher;
import org.eclipse.jetty.fcgi.generator.Generator;
import org.eclipse.jetty.fcgi.generator.ServerGenerator;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.server.HttpTransport;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpTransportOverFCGI implements HttpTransport
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpTransportOverFCGI.class);

    private final ServerGenerator generator;
    private final Flusher flusher;
    private final int request;
    private volatile boolean shutdown;
    private volatile boolean aborted;

    public HttpTransportOverFCGI(ByteBufferPool byteBufferPool, boolean useDirectByteBuffers, boolean sendStatus200, Flusher flusher, int request)
    {
        this.generator = new ServerGenerator(byteBufferPool, useDirectByteBuffers, sendStatus200);
        this.flusher = flusher;
        this.request = request;
    }

    @Override
    public void send(MetaData.Request request, MetaData.Response response, ByteBuffer content, boolean lastContent, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("send {} {} l={}", this, request, lastContent);
        boolean head = HttpMethod.HEAD.is(request.getMethod());
        if (response != null)
        {
            commit(response, head, content, lastContent, callback);
        }
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
