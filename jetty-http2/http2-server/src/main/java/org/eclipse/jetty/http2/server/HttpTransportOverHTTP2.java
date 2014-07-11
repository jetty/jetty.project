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

package org.eclipse.jetty.http2.server;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.http.FinalMetaData;
import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.IStream;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.server.HttpTransport;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class HttpTransportOverHTTP2 implements HttpTransport
{
    private static final Logger LOG = Log.getLogger(HttpTransportOverHTTP2.class);

    private final AtomicBoolean commit = new AtomicBoolean();
    private final Callback commitCallback = new CommitCallback();
    private final IStream stream;
    private final HeadersFrame request;

    public HttpTransportOverHTTP2(IStream stream, HeadersFrame request)
    {
        this.stream = stream;
        this.request = request;
    }

    @Override
    public void send(HttpGenerator.ResponseInfo info, ByteBuffer content, boolean lastContent, Callback callback)
    {
        MetaData.Request metaData = (MetaData.Request)request.getMetaData();
        boolean isHeadRequest = HttpMethod.HEAD.is(metaData.getMethod());
        boolean hasContent = BufferUtil.hasContent(content) && !isHeadRequest;

        if (commit.compareAndSet(false, true))
        {
            boolean endStream = !hasContent && lastContent;
            commit(info, endStream, !hasContent ? callback : commitCallback);
        }
        else
        {
            callback.failed(new IllegalStateException());
        }

        if (hasContent)
        {
            send(content, lastContent, callback);
        }
    }

    private void commit(HttpGenerator.ResponseInfo info, boolean endStream, Callback callback)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("HTTP2 Response #{}:{}{} {}{}{}",
                    stream.getId(), System.lineSeparator(), HttpVersion.HTTP_2, info.getStatus(),
                    System.lineSeparator(), info.getHttpFields());
        }

        MetaData metaData = new FinalMetaData.Response(HttpVersion.HTTP_2, info.getStatus(), info.getHttpFields());
        HeadersFrame frame = new HeadersFrame(stream.getId(), metaData, null, endStream);
        stream.headers(frame, callback);
    }

    @Override
    public void send(ByteBuffer content, boolean lastContent, Callback callback)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("HTTP2 Response #{}: {} content bytes{}",
                    stream.getId(), content.remaining(), lastContent ? " (last chunk)" : "");
        }
        DataFrame frame = new DataFrame(stream.getId(), content, lastContent);
        stream.data(frame, callback);
    }

    @Override
    public void completed()
    {
    }

    @Override
    public void abort()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("HTTP2 Response #{} aborted");
        stream.getSession().disconnect();
    }

    private class CommitCallback implements Callback
    {
        @Override
        public void succeeded()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("HTTP2 Response #{} committed", stream.getId());
        }

        @Override
        public void failed(Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("HTTP2 Response #" + stream.getId() + " failed to commit", x);
            stream.getSession().disconnect();
        }
    }
}
