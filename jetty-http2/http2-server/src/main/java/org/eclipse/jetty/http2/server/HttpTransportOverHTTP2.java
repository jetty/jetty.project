//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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
import java.util.function.Supplier;

import org.eclipse.jetty.http.HttpFields;
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
    private MetaData metaData;

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
            metaData = info;
            int status = info.getStatus();
            boolean interimResponse = status == HttpStatus.CONTINUE_100 || status == HttpStatus.PROCESSING_102;
            if (interimResponse)
            {
                // Must not commit interim responses.
                if (hasContent)
                {
                    callback.failed(new IllegalStateException("Interim response cannot have content"));
                }
                else
                {
                    if (transportCallback.start(callback, false))
                        sendHeadersFrame(info, false, transportCallback);
                }
            }
            else
            {
                if (commit.compareAndSet(false, true))
                {
                    if (hasContent)
                    {
                        Callback commitCallback = new Callback.Nested(callback)
                        {
                            @Override
                            public void succeeded()
                            {
                                if (lastContent)
                                {
                                    Supplier<HttpFields> trailers = info.getTrailerSupplier();
                                    if (transportCallback.start(new SendTrailers(getCallback(), trailers), false))
                                        sendDataFrame(content, true, trailers == null, transportCallback);
                                }
                                else
                                {
                                    if (transportCallback.start(getCallback(), false))
                                        sendDataFrame(content, false, false, transportCallback);
                                }
                            }
                        };
                        if (transportCallback.start(commitCallback, true))
                            sendHeadersFrame(info, false, transportCallback);
                    }
                    else
                    {
                        if (lastContent)
                        {
                            Supplier<HttpFields> trailers = info.getTrailerSupplier();
                            if (transportCallback.start(new SendTrailers(callback, trailers), true))
                                sendHeadersFrame(info, trailers == null, transportCallback);
                        }
                        else
                        {
                            if (transportCallback.start(callback, true))
                                sendHeadersFrame(info, false, transportCallback);
                        }
                    }
                }
                else
                {
                    callback.failed(new IllegalStateException("committed"));
                }
            }
        }
        else
        {
            if (hasContent || lastContent)
            {
                if (lastContent)
                {
                    Supplier<HttpFields> trailers = metaData.getTrailerSupplier();
                    if (transportCallback.start(new SendTrailers(callback, trailers), false))
                        sendDataFrame(content, true, trailers == null, transportCallback);
                }
                else
                {
                    if (transportCallback.start(callback, false))
                        sendDataFrame(content, false, false, transportCallback);
                }
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

    private void sendHeadersFrame(MetaData.Response info, boolean endStream, Callback callback)
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

    private void sendDataFrame(ByteBuffer content, boolean lastContent, boolean endStream, Callback callback)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("HTTP2 Response #{}/{}: {} content bytes{}",
                    stream.getId(), Integer.toHexString(stream.getSession().hashCode()),
                    content.remaining(), lastContent ? " (last chunk)" : "");
        }
        DataFrame frame = new DataFrame(stream.getId(), content, endStream);
        stream.data(frame, callback);
    }

    private void sendTrailersFrame(MetaData metaData, Callback callback)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("HTTP2 Response #{}/{}: trailers",
                    stream.getId(), Integer.toHexString(stream.getSession().hashCode()));
        }

        HeadersFrame frame = new HeadersFrame(stream.getId(), metaData, null, true);
        stream.headers(frame, callback);
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
        HttpChannelOverHTTP2 channel = (HttpChannelOverHTTP2)stream.getAttachment();
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
        private Throwable failure;
        private boolean commit;

        public boolean start(Callback callback, boolean commit)
        {
            State state;
            Throwable failure;
            synchronized (this)
            {
                state = this.state;
                failure = this.failure;
                if (state == State.IDLE)
                {
                    this.state = State.WRITING;
                    this.callback = callback;
                    this.commit = commit;
                    return true;
                }
            }
            if (failure == null)
                failure = new IllegalStateException("Invalid transport state: " + state);
            callback.failed(failure);
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
                    this.state = State.IDLE;
                    callback = this.callback;
                    this.callback = null;
                    this.commit = false;
                }
            }
            if (LOG.isDebugEnabled())
                LOG.debug("HTTP2 Response #{}/{} {} {}",
                        stream.getId(), Integer.toHexString(stream.getSession().hashCode()),
                        commit ? "commit" : "flush",
                        callback == null ? "failure" : "success");
            if (callback != null)
                callback.succeeded();
        }

        @Override
        public void failed(Throwable failure)
        {
            boolean commit;
            Callback callback = null;
            synchronized (this)
            {
                commit = this.commit;
                // Only fail pending writes, as we
                // may need to write an error page.
                if (state == State.WRITING)
                {
                    this.state = State.FAILED;
                    callback = this.callback;
                    this.callback = null;
                    this.failure = failure;
                }
            }
            if (LOG.isDebugEnabled())
                LOG.debug(String.format("HTTP2 Response #%d/%h %s %s", stream.getId(), stream.getSession(), commit ? "commit" : "flush", callback == null ? "ignored" : "failed"), failure);
            if (callback != null)
                callback.failed(failure);
        }

        @Override
        public InvocationType getInvocationType()
        {
            Callback callback;
            synchronized (this)
            {
                callback = this.callback;
            }
            return callback != null ? callback.getInvocationType() : Callback.super.getInvocationType();
        }

        private boolean onIdleTimeout(Throwable failure)
        {
            boolean result;
            Callback callback = null;
            synchronized (this)
            {
                // Ignore idle timeouts if not writing,
                // as the application may be suspended.
                result = state == State.WRITING;
                if (result)
                {
                    this.state = State.TIMEOUT;
                    callback = this.callback;
                    this.callback = null;
                    this.failure = failure;
                }
            }
            if (LOG.isDebugEnabled())
                LOG.debug(String.format("HTTP2 Response #%d/%h idle timeout", stream.getId(), stream.getSession()), failure);
            if (result)
                callback.failed(failure);
            return result;
        }
    }

    private enum State
    {
        IDLE, WRITING, FAILED, TIMEOUT
    }

    private class SendTrailers extends Callback.Nested
    {
        private final Supplier<HttpFields> trailers;

        private SendTrailers(Callback callback, Supplier<HttpFields> trailers)
        {
            super(callback);
            this.trailers = trailers;
        }

        @Override
        public void succeeded()
        {
            if (trailers != null)
            {
                if (transportCallback.start(getCallback(), false))
                    sendTrailersFrame(new MetaData(HttpVersion.HTTP_2, trailers.get()), transportCallback);
            }
            else
            {
                super.succeeded();
            }
        }
    }
}
