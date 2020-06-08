//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.eclipse.jetty.http.BadMessageException;
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
                    transportCallback.send(callback, false, c ->
                        sendHeadersFrame(info, false, c));
                }
            }
            else
            {
                if (commit.compareAndSet(false, true))
                {
                    if (lastContent)
                    {
                        long realContentLength = BufferUtil.length(content);
                        long contentLength = info.getContentLength();
                        if (contentLength < 0)
                        {
                            info.setContentLength(realContentLength);
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
                            sendHeadersFrame(info, false, c));
                    }
                    else
                    {
                        if (lastContent)
                        {
                            HttpFields trailers = retrieveTrailers();
                            if (trailers != null)
                            {
                                transportCallback.send(new SendTrailers(callback, trailers), true, c ->
                                    sendHeadersFrame(info, false, c));
                            }
                            else
                            {
                                transportCallback.send(callback, true, c ->
                                    sendHeadersFrame(info, true, c));
                            }
                        }
                        else
                        {
                            transportCallback.send(callback, true, c ->
                                sendHeadersFrame(info, false, c));
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

        stream.push(new PushPromiseFrame(stream.getId(), request), new Promise<Stream>()
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
     * operation in 3 parts: {@code preSend()}, {@code send()} and {@code postSend()}.
     * Asynchronous state changes happening during {@code send()} are stored
     * and only executed in {@code postSend()}, therefore never interfering
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

        private void reset()
        {
            assert Thread.holdsLock(this);
            _state = State.IDLE;
            _callback = null;
            _commit = false;
            _failure = null;
        }

        private void send(Callback callback, boolean commit, Consumer<Callback> consumer)
        {
            Throwable failure = preSend(callback, commit);
            if (failure == null)
            {
                consumer.accept(this);
                postSend();
            }
            else
            {
                callback.failed(failure);
            }
        }

        private Throwable preSend(Callback callback, boolean commit)
        {
            synchronized (this)
            {
                switch (_state)
                {
                    case IDLE:
                    {
                        _state = State.PRE_SEND;
                        _callback = callback;
                        _commit = commit;
                        return null;
                    }
                    default:
                    {
                        Throwable failure = _failure;
                        if (failure == null)
                            failure = new IllegalStateException("Invalid transport state: " + _state);
                        return failure;
                    }
                }
            }
        }

        private void postSend()
        {
            Callback callback;
            boolean commit;
            Throwable failure;
            synchronized (this)
            {
                switch (_state)
                {
                    case PRE_SEND:
                    {
                        // The send has not completed the callback yet,
                        // wait for succeeded() or failed() to be called.
                        _state = State.POST_SEND;
                        return;
                    }
                    case SUCCEED:
                    {
                        // The send already completed successfully, but the
                        // call to succeeded() was delayed, so call it now.
                        callback = _callback;
                        commit = _commit;
                        failure = null;
                        reset();
                        break;
                    }
                    case FAIL:
                    {
                        // The send already completed with a failure, but
                        // the call to failed() was delayed, so call it now.
                        _state = State.FAILED;
                        callback = _callback;
                        commit = _commit;
                        failure = _failure;
                        break;
                    }
                    default:
                    {
                        _state = State.FAILED;
                        _failure = new IllegalStateException("Invalid transport state: " + _state);
                        callback = _callback;
                        commit = _commit;
                        failure = _failure;
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
                    case PRE_SEND:
                    {
                        _state = State.SUCCEED;
                        // Succeeding the callback will be done in postSend().
                        return;
                    }
                    case POST_SEND:
                    {
                        callback = _callback;
                        commit = _commit;
                        reset();
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
                    case PRE_SEND:
                    {
                        _state = State.FAIL;
                        _failure = failure;
                        // Failing the callback will be done in postSend().
                        return;
                    }
                    case IDLE:
                    case POST_SEND:
                    {
                        _state = State.FAILED;
                        _failure = failure;
                        callback = _callback;
                        commit = _commit;
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
                    case POST_SEND:
                    {
                        // The send was started but idle timed out, fail it.
                        _state = State.FAILED;
                        _failure = failure;
                        callback = _callback;
                        timeout = true;
                        break;
                    }
                    case IDLE:
                        // The application may be suspended, ignore the idle timeout.
                    case PRE_SEND:
                        // A send has been started at the same time of an idle timeout;
                        // Ignore the idle timeout and let the write continue normally.
                    case SUCCEED:
                    case FAIL:
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
                        _state = State.FAILED;
                        _failure = new IllegalStateException("Invalid transport state: " + _state, failure);
                        callback = _callback;
                        if (callback == null)
                            callback = Callback.NOOP;
                        timeout = true;
                        failure = _failure;
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
         *   <li>{@link #PRE_SEND}, when {@link TransportCallback#send(Callback, boolean, Consumer)}
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
         *   <li>{@link #SUCCEED}, when {@link TransportCallback#succeeded()}
         *   is called synchronously because the send succeeded</li>
         *   <li>{@link #FAIL}, when {@link TransportCallback#failed(Throwable)}
         *   is called synchronously because the send failed</li>
         *   <li>{@link #POST_SEND}, when {@link TransportCallback#postSend()}
         *   is called before the send completes</li>
         * </ul>
         */
        PRE_SEND,
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
        POST_SEND,
        /**
         * <p>A send was initiated and succeeded, but {@link TransportCallback#postSend()}
         * has not been called yet.</p>
         * <p>This state indicates that the success actions (such as notifying the
         * {@link TransportCallback} nested callback) must be performed when
         * {@link TransportCallback#postSend()} is called.</p>
         * <p>Next states could be:</p>
         * <ul>
         *   <li>{@link #IDLE}, when {@link TransportCallback#postSend()}
         *   is called</li>
         * </ul>
         */
        SUCCEED,
        /**
         * <p>A send was initiated and failed, but {@link TransportCallback#postSend()}
         * has not been called yet.</p>
         * <p>This state indicates that the failure actions (such as notifying the
         * {@link TransportCallback} nested callback) must be performed when
         * {@link TransportCallback#postSend()} is called.</p>
         * <p>Next states could be:</p>
         * <ul>
         *   <li>{@link #FAILED}, when {@link TransportCallback#postSend()}
         *   is called</li>
         * </ul>
         */
        FAIL,
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
