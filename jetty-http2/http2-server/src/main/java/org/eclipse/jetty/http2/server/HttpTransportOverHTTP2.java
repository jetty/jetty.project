//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.IStream;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PushPromiseFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpChannel;
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
    private final HTTP2ServerConnection connection;
    private IStream stream;

    public HttpTransportOverHTTP2(Connector connector, HTTP2ServerConnection connection)
    {
        this.connector = connector;
        this.connection = connection;
    }

    @Override
    public boolean isOptimizedForDirectBuffers()
    {
        // Because sent buffers are passed directly to the endpoint without
        // copying we can defer to the endpoint
        return connection.getEndPoint().isOptimizedForDirectBuffers();
    }
    
    public IStream getStream()
    {
        return stream;
    }

    public void setStream(IStream stream)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} setStream {}", this, stream.getId());
        this.stream = stream;
    }

    public void recycle()
    {
        this.stream = null;
        commit.set(false);
    }

    @Override
    public void send(MetaData.Response info, boolean isHeadRequest, ByteBuffer content, boolean lastContent, Callback callback)
    {
        // info != null | content != 0 | last = true => commit + send/end
        // info != null | content != 0 | last = false => commit + send
        // info != null | content == 0 | last = true => commit/end
        // info != null | content == 0 | last = false => commit
        // info == null | content != 0 | last = true => send/end
        // info == null | content != 0 | last = false => send
        // info == null | content == 0 | last = true => send/end
        // info == null | content == 0 | last = false => noop

        boolean hasContent = BufferUtil.hasContent(content) && !isHeadRequest;

        if (info != null)
        {
            if (commit.compareAndSet(false, true))
            {
                if (hasContent)
                {
                    commit(info, false, commitCallback);
                    send(content, lastContent, callback);
                }
                else
                {
                    commit(info, lastContent, callback);
                }
            }
            else
            {
                callback.failed(new IllegalStateException("committed"));
            }
        }
        else
        {
            if (hasContent || lastContent)
            {
                send(content, lastContent, callback);
            }
            else
            {
                callback.succeeded();
            }
        }
    }

    @Override
    public boolean isPushSupported()
    {
        return stream.getSession().isPushEnabled();
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

        if (LOG.isDebugEnabled())
            LOG.debug("HTTP/2 Push {}",request);
        
        stream.push(new PushPromiseFrame(stream.getId(), 0, request), new Promise<Stream>()
        {
            @Override
            public void succeeded(Stream pushStream)
            {
                connection.onPush(connector, (IStream)pushStream, request);
            }

            @Override
            public void failed(Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Could not push " + request, x);
            }
        });
    }

    private void commit(MetaData.Response info, boolean endStream, Callback callback)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("HTTP2 Response #{}:{}{} {}{}{}",
                    stream.getId(), System.lineSeparator(), HttpVersion.HTTP_2, info.getStatus(),
                    System.lineSeparator(), info.getFields());
        }

        HeadersFrame frame = new HeadersFrame(stream.getId(), info, null, endStream);
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
    public void onCompleted()
    {
        if (!stream.isClosed())
        {
            // If the stream is not closed, it is still reading the request content.
            // Send a reset to the other end so that it stops sending data.
            stream.reset(new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), Callback.Adapter.INSTANCE);
            // Now that this stream is reset, in-flight data frames will be consumed and discarded.
            // Consume the existing queued data frames to avoid stalling the flow control.
            HttpChannel channel = (HttpChannel)stream.getAttribute(IStream.CHANNEL_ATTRIBUTE);
            channel.getRequest().getHttpInput().consumeAll();
        }
    }

    @Override
    public void abort(Throwable failure)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("HTTP2 Response #{} aborted", stream.getId());
        stream.reset(new ResetFrame(stream.getId(), ErrorCode.INTERNAL_ERROR.code), Callback.Adapter.INSTANCE);
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
        }
    }
}
