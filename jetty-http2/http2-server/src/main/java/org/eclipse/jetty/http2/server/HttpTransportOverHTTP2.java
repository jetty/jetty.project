//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.http.HttpStatus;
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
    private final TransportCallback transportCallback = new TransportCallback();
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
        boolean hasContent = BufferUtil.hasContent(content) && !isHeadRequest;

        if (info != null)
        {
            int status = info.getStatus();
            boolean informational = HttpStatus.isInformational(status) && status != HttpStatus.SWITCHING_PROTOCOLS_101;
            boolean committed = false;
            if (!informational)
                committed = commit.compareAndSet(false, true);

            if (committed || informational)
            {
                if (hasContent)
                {
                    Callback commitCallback = new Callback.Nested(callback)
                    {
                        @Override
                        public void succeeded()
                        {
                            if (transportCallback.start(callback, false))
                                send(content, lastContent, transportCallback);
                        }
                    };
                    if (transportCallback.start(commitCallback, true))
                        commit(info, false, transportCallback);
                }
                else
                {
                    if (transportCallback.start(callback, false))
                        commit(info, lastContent, transportCallback);
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
                if (transportCallback.start(callback, false))
                    send(content, lastContent, transportCallback);
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
            LOG.debug("HTTP/2 Push {}", request);

        stream.push(new PushPromiseFrame(stream.getId(), 0, request), new Promise<Stream>()
        {
            @Override
            public void succeeded(Stream pushStream)
            {
                connection.push(connector, (IStream)pushStream, request);
            }

            @Override
            public void failed(Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Could not push " + request, x);
            }
        }, new Stream.Listener.Adapter()); // TODO: handle reset from the client ?
    }

    private void commit(MetaData.Response info, boolean endStream, Callback callback)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("HTTP2 Response #{}/{}:{}{} {}{}{}",
                    stream.getId(), Integer.toHexString(stream.getSession().hashCode()),
                    System.lineSeparator(), HttpVersion.HTTP_2, info.getStatus(),
                    System.lineSeparator(), info.getFields());
        }

        HeadersFrame frame = new HeadersFrame(stream.getId(), info, null, endStream);
        stream.headers(frame, callback);
    }

    private void send(ByteBuffer content, boolean lastContent, Callback callback)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("HTTP2 Response #{}/{}: {} content bytes{}",
                    stream.getId(), Integer.toHexString(stream.getSession().hashCode()),
                    content.remaining(), lastContent ? " (last chunk)" : "");
        }
        DataFrame frame = new DataFrame(stream.getId(), content, lastContent);
        stream.data(frame, callback);
    }

    public void onStreamFailure(Throwable failure)
    {
        transportCallback.failed(failure);
    }

    public boolean onStreamTimeout(Throwable failure)
    {
        return transportCallback.onIdleTimeout(failure);
    }

    @Override
    public void onCompleted()
    {
        // If the stream is not closed, it is still reading the request content.
        // Send a reset to the other end so that it stops sending data.
        if (!stream.isClosed())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("HTTP2 Response #{}: unconsumed request content, resetting stream", stream.getId());
            stream.reset(new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), Callback.NOOP);
        }

        // Consume the existing queued data frames to
        // avoid stalling the session flow control.
        HttpChannelOverHTTP2 channel = (HttpChannelOverHTTP2)stream.getAttribute(IStream.CHANNEL_ATTRIBUTE);
        if (channel != null)
            channel.consumeInput();
    }

    @Override
    public void abort(Throwable failure)
    {
        IStream stream = this.stream;
        if (LOG.isDebugEnabled())
            LOG.debug("HTTP2 Response #{}/{} aborted", stream == null ? -1 : stream.getId(),
                    stream == null ? -1 : Integer.toHexString(stream.getSession().hashCode()));
        if (stream != null)
            stream.reset(new ResetFrame(stream.getId(), ErrorCode.INTERNAL_ERROR.code), Callback.NOOP);
    }

    private class TransportCallback implements Callback
    {
        private State state = State.IDLE;
        private Callback callback;
        private boolean commit;

        public boolean start(Callback callback, boolean commit)
        {
            State state;
            synchronized (this)
            {
                state = this.state;
                if (state == State.IDLE)
                {
                    this.state = State.WRITING;
                    this.callback = callback;
                    this.commit = commit;
                    return true;
                }
            }
            callback.failed(new IllegalStateException("Invalid transport state: " + state));
            return false;
        }

        @Override
        public void succeeded()
        {
            boolean commit;
            Callback callback = null;
            synchronized (this)
            {
                commit = this.commit;
                if (state == State.WRITING)
                {
                    callback = this.callback;
                    this.callback = null;
                    this.state = State.IDLE;
                }
            }
            if (LOG.isDebugEnabled())
                LOG.debug("HTTP2 Response #{} {}", stream.getId(), commit ? "committed" : "flushed content");
            if (callback != null)
                callback.succeeded();
        }

        @Override
        public void failed(Throwable x)
        {
            boolean commit;
            Callback callback = null;
            synchronized (this)
            {
                commit = this.commit;
                if (state == State.WRITING)
                {
                    callback = this.callback;
                    this.callback = null;
                    this.state = State.FAILED;
                }
            }
            if (LOG.isDebugEnabled())
                LOG.debug("HTTP2 Response #" + stream.getId() + " failed to " + (commit ? "commit" : "flush"), x);
            if (callback != null)
                callback.failed(x);
        }

        @Override
        public InvocationType getInvocationType()
        {
            Callback callback;
            synchronized (this)
            {
                callback = this.callback;
            }
            return callback.getInvocationType();
        }

        private boolean onIdleTimeout(Throwable failure)
        {
            boolean result;
            Callback callback = null;
            synchronized (this)
            {
                result = state == State.WRITING;
                if (result)
                {
                    callback = this.callback;
                    this.callback = null;
                    this.state = State.TIMEOUT;
                }
            }
            if (LOG.isDebugEnabled())
                LOG.debug("HTTP2 Response #" + stream.getId() + " idle timeout", failure);
            if (result)
                callback.failed(failure);
            return result;
        }
    }

    private enum State
    {
        IDLE, WRITING, FAILED, TIMEOUT
    }
}
