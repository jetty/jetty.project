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

import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.IStream;
import org.eclipse.jetty.http2.ResetException;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PushPromiseFrame;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpTransport;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class HttpTransportOverHTTP2 implements HttpTransport
{
    private static final Logger LOG = Log.getLogger(HttpTransportOverHTTP2.class);

    private final AtomicBoolean commit = new AtomicBoolean();
    private final Callback commitCallback = new CommitCallback();
    private final Connector connector;
    private final HttpConfiguration httpConfiguration;
    private final EndPoint endPoint;
    private final IStream stream;
    private final MetaData.Request request;

    public HttpTransportOverHTTP2(Connector connector, HttpConfiguration httpConfiguration, EndPoint endPoint, IStream stream, MetaData.Request request)
    {
        this.connector = connector;
        this.httpConfiguration = httpConfiguration;
        this.endPoint = endPoint;
        this.stream = stream;
        this.request = request;
    }

    @Override
    public void send(HttpGenerator.ResponseInfo info, ByteBuffer content, boolean lastContent, Callback callback)
    {
        boolean isHeadRequest = HttpMethod.HEAD.is(request.getMethod());

        // info != null | content != 0 | last = true => commit + send/end
        // info != null | content != 0 | last = false => commit + send
        // info != null | content == 0 | last = true => commit/end
        // info != null | content == 0 | last = false => commit
        // info == null | content != 0 | last = true => send/end
        // info == null | content != 0 | last = false => send
        // info == null | content == 0 | last = true => send/end
        // info == null | content == 0 | last = false => noop

        boolean hasContent = BufferUtil.hasContent(content) && !isHeadRequest;
        boolean sendContent = hasContent || (info == null && lastContent);

        if (info != null)
        {
            if (commit.compareAndSet(false, true))
            {
                boolean endStream = !hasContent && lastContent;
                commit(info, endStream, sendContent ? commitCallback : callback);
            }
            else
            {
                callback.failed(new IllegalStateException());
            }
        }

        if (sendContent)
        {
            send(content, lastContent, callback);
        }
    }

    @Override
    public void push(final MetaData.Request request)
    {
        if (!stream.getSession().isPushEnabled())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("HTTP/2 Push disabled for {}", request);
            return;
        }

        stream.push(new PushPromiseFrame(stream.getId(), 0, request), new Promise<Stream>()
        {
            @Override
            public void succeeded(Stream pushStream)
            {
                HttpTransportOverHTTP2 transport = new HttpTransportOverHTTP2(connector, httpConfiguration, endPoint, (IStream)pushStream, request);
                HttpInputOverHTTP2 input = new HttpInputOverHTTP2();
                HttpChannelOverHTTP2 channel = new HttpChannelOverHTTP2(connector, httpConfiguration, endPoint, transport, input, pushStream);
                pushStream.setAttribute(IStream.CHANNEL_ATTRIBUTE, channel);

                channel.onPushRequest(request);
            }

            @Override
            public void failed(Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Could not push " + request, x);
                stream.getSession().disconnect();
            }
        });
    }
    
    private void commit(HttpGenerator.ResponseInfo info, boolean endStream, Callback callback)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("HTTP2 Response #{}:{}{} {}{}{}",
                    stream.getId(), System.lineSeparator(), HttpVersion.HTTP_2, info.getStatus(),
                    System.lineSeparator(), info.getHttpFields());
        }

        MetaData metaData = new MetaData.Response(HttpVersion.HTTP_2, info.getStatus(), info.getHttpFields());
        HeadersFrame frame = new HeadersFrame(stream.getId(), metaData, null, endStream);
        stream.headers(frame, callback);
    }
    
    private void send(ByteBuffer content, boolean lastContent, Callback callback)
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
    public void abort(Throwable failure)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("HTTP2 Response #{} aborted", stream.getId());
        if (!(failure instanceof ResetException))
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
