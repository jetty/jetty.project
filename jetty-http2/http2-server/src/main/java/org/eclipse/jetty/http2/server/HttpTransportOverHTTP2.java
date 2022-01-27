//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http2.server;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
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
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpTransport;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpTransportOverHTTP2 implements HttpTransport
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpTransportOverHTTP2.class);

    private final AtomicBoolean commit = new AtomicBoolean();
    private final TransportCallback transportCallback = new TransportCallback();
    private final Connector connector;
    private final HTTP2ServerConnection connection;
    private IStream stream;
    private MetaData.Response metaData;

    public HttpTransportOverHTTP2(Connector connector, HTTP2ServerConnection connection)
    {
        this.connector = connector;
        this.connection = connection;
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
    public void send(MetaData.Request request, MetaData.Response response, ByteBuffer content, boolean lastContent, Callback callback)
    {
        if (response != null)
            sendHeaders(request, response, content, lastContent, callback);
        else
            sendContent(request, content, lastContent, callback);
    }

    public void sendHeaders(MetaData.Request request, MetaData.Response response, ByteBuffer content, boolean lastContent, Callback callback)
    {
        metaData = response;

        HeadersFrame headersFrame;
        DataFrame dataFrame = null;
        HeadersFrame trailersFrame = null;

        boolean isHeadRequest = HttpMethod.HEAD.is(request.getMethod());
        boolean hasContent = BufferUtil.hasContent(content) && !isHeadRequest;
        int status = response.getStatus();
        boolean interimResponse = status == HttpStatus.CONTINUE_100 || status == HttpStatus.PROCESSING_102;
        if (interimResponse)
        {
            // Must not commit interim responses.
            if (hasContent)
            {
                callback.failed(new IllegalStateException("Interim response cannot have content"));
                return;
            }
            headersFrame = new HeadersFrame(stream.getId(), metaData, null, false);
        }
        else
        {
            if (commit.compareAndSet(false, true))
            {
                if (lastContent)
                {
                    long realContentLength = BufferUtil.length(content);
                    long contentLength = response.getContentLength();
                    if (contentLength < 0)
                    {
                        metaData = new MetaData.Response(
                            response.getHttpVersion(),
                            response.getStatus(),
                            response.getReason(),
                            response.getFields(),
                            realContentLength,
                            response.getTrailerSupplier()
                        );
                    }
                    else if (hasContent && contentLength != realContentLength)
                    {
                        callback.failed(new BadMessageException(HttpStatus.INTERNAL_SERVER_ERROR_500, String.format("Incorrect Content-Length %d!=%d", contentLength, realContentLength)));
                        return;
                    }
                }

                if (hasContent)
                {
                    headersFrame = new HeadersFrame(stream.getId(), metaData, null, false);
                    if (lastContent)
                    {
                        HttpFields trailers = retrieveTrailers();
                        if (trailers == null)
                        {
                            dataFrame = new DataFrame(stream.getId(), content, true);
                        }
                        else
                        {
                            dataFrame = new DataFrame(stream.getId(), content, false);
                            trailersFrame = new HeadersFrame(stream.getId(), new MetaData(HttpVersion.HTTP_2, trailers), null, true);
                        }
                    }
                    else
                    {
                        dataFrame = new DataFrame(stream.getId(), content, false);
                    }
                }
                else
                {
                    if (lastContent)
                    {
                        if (isTunnel(request, metaData))
                        {
                            headersFrame = new HeadersFrame(stream.getId(), metaData, null, false);
                        }
                        else
                        {
                            HttpFields trailers = retrieveTrailers();
                            if (trailers == null)
                            {
                                headersFrame = new HeadersFrame(stream.getId(), metaData, null, true);
                            }
                            else
                            {
                                headersFrame = new HeadersFrame(stream.getId(), metaData, null, false);
                                trailersFrame = new HeadersFrame(stream.getId(), new MetaData(HttpVersion.HTTP_2, trailers), null, true);
                            }
                        }
                    }
                    else
                    {
                        headersFrame = new HeadersFrame(stream.getId(), metaData, null, false);
                    }
                }
            }
            else
            {
                callback.failed(new IllegalStateException("committed"));
                return;
            }
        }

        HeadersFrame hf = headersFrame;
        DataFrame df = dataFrame;
        HeadersFrame tf = trailersFrame;

        transportCallback.send(callback, true, c ->
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("HTTP2 Response #{}/{}:{}{} {}{}{}",
                    stream.getId(), Integer.toHexString(stream.getSession().hashCode()),
                    System.lineSeparator(), HttpVersion.HTTP_2, metaData.getStatus(),
                    System.lineSeparator(), metaData.getFields());
            }
            stream.send(new IStream.FrameList(hf, df, tf), c);
        });
    }

    public void sendContent(MetaData.Request request, ByteBuffer content, boolean lastContent, Callback callback)
    {
        boolean isHeadRequest = HttpMethod.HEAD.is(request.getMethod());
        boolean hasContent = BufferUtil.hasContent(content) && !isHeadRequest;
        if (hasContent || (lastContent && !isTunnel(request, metaData)))
        {
            if (lastContent)
            {
                HttpFields trailers = retrieveTrailers();
                if (trailers == null)
                {
                    transportCallback.send(callback, false, c ->
                        sendDataFrame(content, true, true, c));
                }
                else
                {
                    SendTrailers sendTrailers = new SendTrailers(callback, trailers);
                    if (hasContent)
                    {
                        transportCallback.send(sendTrailers, false, c ->
                            sendDataFrame(content, true, false, c));
                    }
                    else
                    {
                        sendTrailers.succeeded();
                    }
                }
            }
            else
            {
                transportCallback.send(callback, false, c ->
                    sendDataFrame(content, false, false, c));
            }
        }
        else
        {
            callback.succeeded();
        }
    }

    private HttpFields retrieveTrailers()
    {
        Supplier<HttpFields> supplier = metaData.getTrailerSupplier();
        if (supplier == null)
            return null;
        HttpFields trailers = supplier.get();
        if (trailers == null)
            return null;
        return trailers.size() == 0 ? null : trailers;
    }

    private boolean isTunnel(MetaData.Request request, MetaData.Response response)
    {
        return MetaData.isTunnel(request.getMethod(), response.getStatus());
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

        stream.push(new PushPromiseFrame(stream.getId(), request), new Promise<>()
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
                    LOG.debug("Could not push {}", request, x);
            }
        }, new Stream.Listener.Adapter()); // TODO: handle reset from the client ?
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
        transportCallback.abort(failure);
    }

    public boolean onStreamTimeout(Throwable failure)
    {
        return transportCallback.idleTimeout(failure);
    }

    /**
     * @return true if error sent, false if upgraded or aborted.
     */
    boolean prepareUpgrade()
    {
        HttpChannelOverHTTP2 channel = (HttpChannelOverHTTP2)stream.getAttachment();
        Request request = channel.getRequest();
        if (request.getHttpInput().hasContent())
            return channel.sendErrorOrAbort("Unexpected content in CONNECT request");

        Connection connection = (Connection)request.getAttribute(UPGRADE_CONNECTION_ATTRIBUTE);
        if (connection == null)
            return channel.sendErrorOrAbort("No UPGRADE_CONNECTION_ATTRIBUTE available");

        EndPoint endPoint = connection.getEndPoint();
        endPoint.upgrade(connection);
        stream.setAttachment(endPoint);

        // Only now that we have switched the attachment, we can demand DATA frames to process them.
        stream.demand(1);

        if (LOG.isDebugEnabled())
            LOG.debug("Upgrading to {}", connection);

        return false;
    }

    @Override
    public void onCompleted()
    {
        Object attachment = stream.getAttachment();
        if (attachment instanceof HttpChannelOverHTTP2)
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
            HttpChannelOverHTTP2 channel = (HttpChannelOverHTTP2)attachment;
            channel.consumeInput();
        }
    }

    @Override
    public void abort(Throwable failure)
    {
        IStream stream = this.stream;
        if (LOG.isDebugEnabled())
            LOG.debug("HTTP2 Response #{}/{} aborted", stream == null ? -1 : stream.getId(),
                stream == null ? -1 : Integer.toHexString(stream.getSession().hashCode()));
        if (stream != null)
            stream.reset(new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), Callback.NOOP);
    }

    /**
     * <p>Callback that controls sends initiated by the transport, by eventually
     * notifying a nested callback.</p>
     * <p>There are 3 sources of concurrency after a send is initiated:</p>
     * <ul>
     *   <li>the completion of the send operation, either success or failure</li>
     *   <li>an asynchronous failure coming from the read side such as a stream
     *   being reset, or the connection being closed</li>
     *   <li>an asynchronous idle timeout</li>
     * </ul>
     *
     * @see State
     */
    private class TransportCallback implements Callback
    {
        private final AutoLock _lock = new AutoLock();
        private State _state = State.IDLE;
        private Callback _callback;
        private boolean _commit;
        private Throwable _failure;

        private void reset(Throwable failure)
        {
            assert _lock.isHeldByCurrentThread();
            _state = failure != null ? State.FAILED : State.IDLE;
            _callback = null;
            _commit = false;
            _failure = failure;
        }

        private void send(Callback callback, boolean commit, Consumer<Callback> sendFrame)
        {
            Throwable failure = sending(callback, commit);
            if (failure == null)
                sendFrame.accept(this);
            else
                callback.failed(failure);
        }

        private void abort(Throwable failure)
        {
            failed(failure);
        }

        private Throwable sending(Callback callback, boolean commit)
        {
            try (AutoLock l = _lock.lock())
            {
                switch (_state)
                {
                    case IDLE:
                    {
                        _state = State.SENDING;
                        _callback = callback;
                        _commit = commit;
                        return null;
                    }
                    case FAILED:
                    {
                        return _failure;
                    }
                    default:
                    {
                        return new IllegalStateException("Invalid transport state: " + _state);
                    }
                }
            }
        }

        @Override
        public void succeeded()
        {
            Callback callback;
            boolean commit;
            try (AutoLock l = _lock.lock())
            {
                if (_state != State.SENDING)
                {
                    // This thread lost the race to succeed the current
                    // send, as other threads likely already failed it.
                    return;
                }
                callback = _callback;
                commit = _commit;
                reset(null);
            }
            if (LOG.isDebugEnabled())
                LOG.debug("HTTP2 Response #{}/{} {} success",
                    stream.getId(), Integer.toHexString(stream.getSession().hashCode()),
                    commit ? "commit" : "flush");
            callback.succeeded();
        }

        @Override
        public void failed(Throwable failure)
        {
            Callback callback;
            boolean commit;
            try (AutoLock l = _lock.lock())
            {
                if (_state != State.SENDING)
                {
                    reset(failure);
                    return;
                }
                callback = _callback;
                commit = _commit;
                reset(failure);
            }
            if (LOG.isDebugEnabled())
                LOG.debug("HTTP2 Response #{}/{} {} failure",
                    stream.getId(), Integer.toHexString(stream.getSession().hashCode()),
                    commit ? "commit" : "flush",
                    failure);
            callback.failed(failure);
        }

        private boolean idleTimeout(Throwable failure)
        {
            Callback callback = null;
            try (AutoLock l = _lock.lock())
            {
                // Ignore idle timeouts if not writing,
                // as the application may be suspended.
                if (_state == State.SENDING)
                {
                    callback = _callback;
                    reset(failure);
                }
            }
            boolean timeout = callback != null;
            if (LOG.isDebugEnabled())
                LOG.debug("HTTP2 Response #{}/{} idle timeout {}",
                    stream.getId(), Integer.toHexString(stream.getSession().hashCode()),
                    timeout ? "expired" : "ignored",
                    failure);
            if (timeout)
                callback.failed(failure);
            return timeout;
        }

        @Override
        public InvocationType getInvocationType()
        {
            Callback callback;
            try (AutoLock l = _lock.lock())
            {
                callback = _callback;
            }
            return callback != null ? callback.getInvocationType() : Callback.super.getInvocationType();
        }
    }

    /**
     * <p>Send states for {@link TransportCallback}.</p>
     *
     * @see TransportCallback
     */
    private enum State
    {
        /**
         * <p>No send initiated or in progress.</p>
         */
        IDLE,
        /**
         * <p>A send is initiated and possibly in progress.</p>
         */
        SENDING,
        /**
         * <p>The terminal state indicating failure of the send.</p>
         */
        FAILED
    }

    private class SendTrailers extends Callback.Nested
    {
        private final HttpFields trailers;

        private SendTrailers(Callback callback, HttpFields trailers)
        {
            super(callback);
            this.trailers = trailers;
        }

        @Override
        public void succeeded()
        {
            transportCallback.send(getCallback(), false, c ->
                sendTrailersFrame(new MetaData(HttpVersion.HTTP_2, trailers), c));
        }
    }
}
