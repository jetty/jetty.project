//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
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
    public void send(MetaData.Request request, final MetaData.Response response, ByteBuffer content, boolean lastContent, Callback callback)
    {
        boolean isHeadRequest = HttpMethod.HEAD.is(request.getMethod());
        boolean hasContent = BufferUtil.hasContent(content) && !isHeadRequest;
        if (response != null)
        {
            metaData = response;
            int status = response.getStatus();
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
                    transportCallback.send(callback, false, c ->
                        sendHeadersFrame(metaData, false, c));
                }
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
                        Callback commitCallback = new Callback.Nested(callback)
                        {
                            @Override
                            public void succeeded()
                            {
                                if (lastContent)
                                {
                                    HttpFields trailers = retrieveTrailers();
                                    if (trailers != null)
                                    {
                                        transportCallback.send(new SendTrailers(getCallback(), trailers), false, c ->
                                            sendDataFrame(content, true, false, c));
                                    }
                                    else
                                    {
                                        transportCallback.send(getCallback(), false, c ->
                                            sendDataFrame(content, true, true, c));
                                    }
                                }
                                else
                                {
                                    transportCallback.send(getCallback(), false, c ->
                                        sendDataFrame(content, false, false, c));
                                }
                            }
                        };
                        transportCallback.send(commitCallback, true, c ->
                            sendHeadersFrame(metaData, false, c));
                    }
                    else
                    {
                        if (lastContent)
                        {
                            if (isTunnel(request, metaData))
                            {
                                transportCallback.send(callback, true, c ->
                                    sendHeadersFrame(metaData, false, c));
                            }
                            else
                            {
                                HttpFields trailers = retrieveTrailers();
                                if (trailers != null)
                                {
                                    transportCallback.send(new SendTrailers(callback, trailers), true, c ->
                                        sendHeadersFrame(metaData, false, c));
                                }
                                else
                                {
                                    transportCallback.send(callback, true, c ->
                                        sendHeadersFrame(metaData, true, c));
                                }
                            }
                        }
                        else
                        {
                            transportCallback.send(callback, true, c ->
                                sendHeadersFrame(metaData, false, c));
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
            if (hasContent || (lastContent && !isTunnel(request, metaData)))
            {
                if (lastContent)
                {
                    HttpFields trailers = retrieveTrailers();
                    if (trailers != null)
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
                    else
                    {
                        transportCallback.send(callback, false, c ->
                            sendDataFrame(content, true, true, c));
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
        return HttpMethod.CONNECT.is(request.getMethod()) && response.getStatus() == HttpStatus.OK_200;
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
     * <p>The last 2 cases may happen <em>during</em> a send, when the frames
     * are being generated in the flusher.
     * In such cases, this class must avoid that the nested callback is notified
     * while the frame generation is in progress, because the nested callback
     * may modify other states (such as clearing the {@code HttpOutput._buffer})
     * that are accessed during frame generation.</p>
     * <p>The solution implemented in this class works by splitting the send
     * operation in 3 parts: {@code pre-send}, {@code send} and {@code post-send}.
     * Asynchronous state changes happening during {@code send} are stored
     * and only executed in {@code post-send}, therefore never interfering
     * with frame generation.</p>
     *
     * @see State
     */
    private class TransportCallback implements Callback
    {
        private State _state = State.IDLE;
        private Callback _callback;
        private boolean _commit;
        private Throwable _failure;

        private void reset(Throwable failure)
        {
            assert Thread.holdsLock(this);
            _state = failure != null ? State.FAILED : State.IDLE;
            _callback = null;
            _commit = false;
            _failure = failure;
        }

        private void send(Callback callback, boolean commit, Consumer<Callback> sendFrame)
        {
            Throwable failure = sending(callback, commit);
            if (failure == null)
            {
                sendFrame.accept(this);
                pending();
            }
            else
            {
                callback.failed(failure);
            }
        }

        private Throwable sending(Callback callback, boolean commit)
        {
            synchronized (this)
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

        private void pending()
        {
            Callback callback;
            boolean commit;
            Throwable failure;
            synchronized (this)
            {
                switch (_state)
                {
                    case SENDING:
                    {
                        // The send has not completed the callback yet,
                        // wait for succeeded() or failed() to be called.
                        _state = State.PENDING;
                        return;
                    }
                    case SUCCEEDING:
                    {
                        // The send already completed successfully, but the
                        // call to succeeded() was delayed, so call it now.
                        callback = _callback;
                        commit = _commit;
                        failure = null;
                        reset(null);
                        break;
                    }
                    case FAILING:
                    {
                        // The send already completed with a failure, but
                        // the call to failed() was delayed, so call it now.
                        callback = _callback;
                        commit = _commit;
                        failure = _failure;
                        reset(failure);
                        break;
                    }
                    default:
                    {
                        callback = _callback;
                        commit = _commit;
                        failure = new IllegalStateException("Invalid transport state: " + _state);
                        reset(failure);
                        break;
                    }
                }
            }
            if (failure == null)
                succeed(callback, commit);
            else
                fail(callback, commit, failure);
        }

        @Override
        public void succeeded()
        {
            Callback callback;
            boolean commit;
            synchronized (this)
            {
                switch (_state)
                {
                    case SENDING:
                    {
                        _state = State.SUCCEEDING;
                        // Succeeding the callback will be done in postSend().
                        return;
                    }
                    case PENDING:
                    {
                        callback = _callback;
                        commit = _commit;
                        reset(null);
                        break;
                    }
                    default:
                    {
                        // This thread lost the race to succeed the current
                        // send, as other threads likely already failed it.
                        return;
                    }
                }
            }
            succeed(callback, commit);
        }

        @Override
        public void failed(Throwable failure)
        {
            Callback callback;
            boolean commit;
            synchronized (this)
            {
                switch (_state)
                {
                    case SENDING:
                    {
                        _state = State.FAILING;
                        _failure = failure;
                        // Failing the callback will be done in postSend().
                        return;
                    }
                    case IDLE:
                    case PENDING:
                    {
                        callback = _callback;
                        commit = _commit;
                        reset(failure);
                        break;
                    }
                    default:
                    {
                        // This thread lost the race to fail the current send,
                        // as other threads already succeeded or failed it.
                        return;
                    }
                }
            }
            fail(callback, commit, failure);
        }

        private boolean idleTimeout(Throwable failure)
        {
            Callback callback;
            boolean timeout;
            synchronized (this)
            {
                switch (_state)
                {
                    case PENDING:
                    {
                        // The send was started but idle timed out, fail it.
                        callback = _callback;
                        timeout = true;
                        reset(failure);
                        break;
                    }
                    case IDLE:
                        // The application may be suspended, ignore the idle timeout.
                    case SENDING:
                        // A send has been started at the same time of an idle timeout;
                        // Ignore the idle timeout and let the write continue normally.
                    case SUCCEEDING:
                    case FAILING:
                        // An idle timeout during these transient states is ignored.
                    case FAILED:
                        // Already failed, ignore the idle timeout.
                    {
                        callback = null;
                        timeout = false;
                        break;
                    }
                    default:
                    {
                        // Should not happen, but just in case.
                        callback = _callback;
                        if (callback == null)
                            callback = Callback.NOOP;
                        timeout = true;
                        failure = new IllegalStateException("Invalid transport state: " + _state, failure);
                        reset(failure);
                        break;
                    }
                }
            }
            idleTimeout(callback, timeout, failure);
            return timeout;
        }

        private void succeed(Callback callback, boolean commit)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("HTTP2 Response #{}/{} {} success",
                    stream.getId(), Integer.toHexString(stream.getSession().hashCode()),
                    commit ? "commit" : "flush");
            callback.succeeded();
        }

        private void fail(Callback callback, boolean commit, Throwable failure)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("HTTP2 Response #{}/{} {} failure",
                    stream.getId(), Integer.toHexString(stream.getSession().hashCode()),
                    commit ? "commit" : "flush",
                    failure);
            if (callback != null)
                callback.failed(failure);
        }

        private void idleTimeout(Callback callback, boolean timeout, Throwable failure)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("HTTP2 Response #{}/{} idle timeout {}",
                    stream.getId(), Integer.toHexString(stream.getSession().hashCode()),
                    timeout ? "expired" : "ignored",
                    failure);
            if (timeout)
                callback.failed(failure);
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
         * <p>Next states could be:</p>
         * <ul>
         *   <li>{@link #SENDING}, when {@link TransportCallback#send(Callback, boolean, Consumer)}
         *   is called by the transport to initiate a send</li>
         *   <li>{@link #FAILED}, when {@link TransportCallback#failed(Throwable)}
         *   is called by an asynchronous failure</li>
         * </ul>
         */
        IDLE,
        /**
         * <p>A send is initiated; the nested callback in {@link TransportCallback}
         * cannot be notified while in this state.</p>
         * <p>Next states could be:</p>
         * <ul>
         *   <li>{@link #SUCCEEDING}, when {@link TransportCallback#succeeded()}
         *   is called synchronously because the send succeeded</li>
         *   <li>{@link #FAILING}, when {@link TransportCallback#failed(Throwable)}
         *   is called synchronously because the send failed</li>
         *   <li>{@link #PENDING}, when {@link TransportCallback#pending()}
         *   is called before the send completes</li>
         * </ul>
         */
        SENDING,
        /**
         * <p>A send was initiated and is now pending, waiting for the {@link TransportCallback}
         * to be notified of success or failure.</p>
         * <p>Next states could be:</p>
         * <ul>
         *   <li>{@link #IDLE}, when {@link TransportCallback#succeeded()}
         *   is called because the send succeeded</li>
         *   <li>{@link #FAILED}, when {@link TransportCallback#failed(Throwable)}
         *   is called because either the send failed, or an asynchronous failure happened</li>
         * </ul>
         */
        PENDING,
        /**
         * <p>A send was initiated and succeeded, but {@link TransportCallback#pending()}
         * has not been called yet.</p>
         * <p>This state indicates that the success actions (such as notifying the
         * {@link TransportCallback} nested callback) must be performed when
         * {@link TransportCallback#pending()} is called.</p>
         * <p>Next states could be:</p>
         * <ul>
         *   <li>{@link #IDLE}, when {@link TransportCallback#pending()}
         *   is called</li>
         * </ul>
         */
        SUCCEEDING,
        /**
         * <p>A send was initiated and failed, but {@link TransportCallback#pending()}
         * has not been called yet.</p>
         * <p>This state indicates that the failure actions (such as notifying the
         * {@link TransportCallback} nested callback) must be performed when
         * {@link TransportCallback#pending()} is called.</p>
         * <p>Next states could be:</p>
         * <ul>
         *   <li>{@link #FAILED}, when {@link TransportCallback#pending()}
         *   is called</li>
         * </ul>
         */
        FAILING,
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
