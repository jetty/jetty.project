//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http3;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.frames.DataFrame;
import org.eclipse.jetty.http3.frames.Frame;
import org.eclipse.jetty.http3.frames.GoAwayFrame;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.frames.SettingsFrame;
import org.eclipse.jetty.http3.parser.ParserListener;
import org.eclipse.jetty.io.CyclicTimeouts;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.quic.api.frames.ConnectionCloseFrame;
import org.eclipse.jetty.quic.common.ProtocolSession;
import org.eclipse.jetty.quic.common.StreamEndPoint;
import org.eclipse.jetty.util.Atomics;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class HTTP3Session extends ContainerLifeCycle implements Session
{
    private static final Logger LOG = LoggerFactory.getLogger(HTTP3Session.class);

    private final AutoLock lock = new AutoLock();
    private final AtomicLong lastStreamId = new AtomicLong(0);
    private final Map<Long, HTTP3Stream> streams = new ConcurrentHashMap<>();
    private final ProtocolSession session;
    private final Session.Listener listener;
    private final AtomicInteger streamCount = new AtomicInteger();
    private final StreamTimeouts streamTimeouts;
    private final ParserListener parserListener;
    private long streamIdleTimeout;
    private CloseState closeState = CloseState.CLOSED;
    private GoAwayFrame goAwaySent;
    private GoAwayFrame goAwayRecv;
    private Runnable zeroStreamsAction;
    private CompletableFuture<Session> shutdown;

    public HTTP3Session(Scheduler scheduler, ProtocolSession session, Session.Listener listener)
    {
        this.session = session;
        this.listener = listener;
        this.streamTimeouts = new StreamTimeouts(scheduler);
        this.parserListener = new FrameListener();
    }

    public ProtocolSession getProtocolSession()
    {
        return session;
    }

    public Session.Listener getListener()
    {
        return listener;
    }

    public ParserListener getParserListener()
    {
        return parserListener;
    }

    public void onOpen()
    {
        closeState = CloseState.NOT_CLOSED;
    }

    @Override
    public SocketAddress getLocalSocketAddress()
    {
        return getProtocolSession().getSession().getLocalSocketAddress();
    }

    @Override
    public SocketAddress getRemoteSocketAddress()
    {
        return getProtocolSession().getSession().getRemoteSocketAddress();
    }

    @Override
    public Collection<Stream> getStreams()
    {
        return List.copyOf(streams.values());
    }

    public long getMaxLocalStreams()
    {
        return session.getSession().getBidirectionalLocalStreamMaxCount();
    }

    @Override
    public void goAway(boolean graceful, Promise.Invocable<Session> promise)
    {
        goAway(newGoAwayFrame(graceful), promise);
    }

    private void goAway(GoAwayFrame frame,  Promise.Invocable<Session> promise)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("goAway with {} on {}", frame, this);

        boolean failStreams = false;
        boolean sendGoAway = false;
        try (AutoLock ignored = lock.lock())
        {
            switch (closeState)
            {
                case NOT_CLOSED:
                {
                    goAwaySent = frame;
                    sendGoAway = true;
                    closeState = CloseState.LOCALLY_CLOSED;
                    if (frame.isGraceful())
                    {
                        // Send the non-graceful GOAWAY when the last stream is destroyed.
                        zeroStreamsAction = () -> goAway(false, Promise.Invocable.noop());
                    }
                    break;
                }
                case LOCALLY_CLOSED:
                {
                    if (frame.isGraceful())
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("already sent {} on {}", goAwaySent, this);
                    }
                    else
                    {
                        // SPEC: see section about connection shutdown.
                        if (goAwaySent.isGraceful() || frame.getLastId() < goAwaySent.getLastId())
                        {
                            goAwaySent = frame;
                            sendGoAway = true;
                        }
                        else
                        {
                            closeState = CloseState.CLOSED;
                            failStreams = true;
                        }
                    }
                    break;
                }
                case REMOTELY_CLOSED:
                {
                    goAwaySent = frame;
                    sendGoAway = true;
                    if (frame.isGraceful())
                    {
                        // Send the non-graceful GOAWAY when the last stream is destroyed.
                        zeroStreamsAction = () -> goAway(false, Promise.Invocable.noop());
                    }
                    else
                    {
                        if (goAwayRecv.isGraceful())
                        {
                            if (LOG.isDebugEnabled())
                                LOG.debug("waiting non-graceful GOAWAY on {}", this);
                        }
                        else
                        {
                            closeState = CloseState.CLOSING;
                            zeroStreamsAction = this::terminate;
                        }
                    }
                    break;
                }
                case CLOSING:
                case CLOSED:
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("already closed on {}", this);
                    break;
                }
                default:
                {
                    throw new IllegalStateException();
                }
            }
        }

        if (sendGoAway)
        {
            writeControlFrame(frame, Callback.from(this::tryRunZeroStreamsAction, Promise.Invocable.toCallback(promise, this)));
        }
        else
        {
            if (failStreams)
            {
                long error = HTTP3ErrorCode.REQUEST_CANCELLED_ERROR.code();
                failStreams(stream -> true, error, new ClosedChannelException());
                terminateAndDisconnect(error, "go_away", promise);
            }
            else
            {
                promise.succeeded(this);
            }
        }
    }

    protected GoAwayFrame newGoAwayFrame(boolean graceful)
    {
        return new GoAwayFrame(lastStreamId.get());
    }

    public CompletableFuture<Session> shutdown()
    {
        CompletableFuture<Session> result;
        try (AutoLock ignored = lock.lock())
        {
            if (shutdown != null)
                return shutdown;
            shutdown = result = new Promise.Completable<>();
        }
        goAway(true, Promise.Invocable.noop());
        return result;
    }

    private void updateLastStreamId(long id)
    {
        Atomics.updateMax(lastStreamId, id);
    }

    public long getIdleTimeout()
    {
        return getProtocolSession().getSession().getIdleTimeout();
    }

    public long getStreamIdleTimeout()
    {
        return streamIdleTimeout;
    }

    public void setStreamIdleTimeout(long streamIdleTimeout)
    {
        this.streamIdleTimeout = streamIdleTimeout;
    }

    void scheduleIdleTimeout(HTTP3Stream stream)
    {
        streamTimeouts.schedule(stream);
    }

    protected HTTP3Stream createStream(StreamEndPoint endPoint)
    {
        long streamId = endPoint.getStream().getId();
        return streams.compute(streamId, (id, stream) ->
        {
            if (stream != null)
                throw new HTTP3Exception.StreamException(HTTP3ErrorCode.REQUEST_REJECTED_ERROR, "duplicate_stream_id");
            return createHTTP3Stream(endPoint, true);
        });
    }

    private HTTP3Stream createHTTP3Stream(StreamEndPoint endPoint, boolean local)
    {
        try (AutoLock ignored = lock.lock())
        {
            if (closeState == CloseState.LOCALLY_CLOSED)
            {
                if (endPoint.getStream().getId() > goAwaySent.getLastId())
                    throw new HTTP3Exception.StreamException(HTTP3ErrorCode.REQUEST_REJECTED_ERROR, "goaway_sent");
            }
            else if (closeState != CloseState.NOT_CLOSED)
            {
                throw new HTTP3Exception.SessionException(HTTP3ErrorCode.REQUEST_REJECTED_ERROR, "session_closed");
            }
            streamCount.incrementAndGet();
        }

        HTTP3Stream stream = newHTTP3Stream(endPoint, local);
        ((HTTP3StreamConnection)endPoint.getConnection()).setStream(stream);
        // Disable the stream idle timeout at the QUIC level,
        // keep only the stream idle timeout at the HTTP/3 level.
        endPoint.setIdleTimeout(0);
        long idleTimeout = getStreamIdleTimeout();
        if (idleTimeout > 0)
            stream.setIdleTimeout(idleTimeout);
        if (!local)
            updateLastStreamId(stream.getId());
        if (LOG.isDebugEnabled())
            LOG.debug("created {}", stream);
        return stream;
    }

    protected abstract HTTP3Stream newHTTP3Stream(StreamEndPoint endPoint, boolean local);

    protected HTTP3Stream getStream(long streamId)
    {
        return streams.get(streamId);
    }

    void removeStream(HTTP3Stream stream)
    {
        boolean removed = streams.remove(stream.getId()) != null;
        if (LOG.isDebugEnabled())
            LOG.debug("removed {} {} on {}", removed, stream, this);
        if (removed)
        {
            getProtocolSession().removeStreamEndPoint(stream.getStreamEndPoint());
            if (streamCount.decrementAndGet() == 0)
                tryRunZeroStreamsAction();
        }
    }

    public abstract void writeControlFrame(Frame frame, Callback callback);

    public abstract void writeMessageFrame(StreamEndPoint streamEndPoint, Frame frame, Callback callback);

    public Map<Long, Long> onPreface()
    {
        Map<Long, Long> settings = notifyPreface();
        if (LOG.isDebugEnabled())
            LOG.debug("application produced settings {} on {}", settings, this);
        return settings;
    }

    protected void onHeaders(long streamId, HeadersFrame frame, boolean wasBlocked)
    {
        MetaData metaData = frame.getMetaData();
        if (metaData.isRequest() || metaData.isResponse())
            throw new HTTP3Exception.StreamException(HTTP3ErrorCode.REQUEST_REJECTED_ERROR, "invalid_metadata");

        StreamEndPoint endPoint = session.getStreamEndPoint(streamId);
        HTTP3Stream stream = getStream(endPoint.getStream().getId());
        if (LOG.isDebugEnabled())
            LOG.debug("received trailer {} on {}", frame, stream);
        if (stream != null)
            stream.onTrailer(frame);
        else
            throw new HTTP3Exception.SessionException(HTTP3ErrorCode.FRAME_UNEXPECTED_ERROR.code(), "invalid_frame_sequence");
    }

    protected void onData(long streamId, DataFrame frame)
    {
        HTTP3Stream stream = getStream(streamId);
        if (LOG.isDebugEnabled())
            LOG.debug("received {} on {}", frame, stream);
        if (stream != null)
            stream.onData(frame);
        else
            throw new HTTP3Exception.SessionException(HTTP3ErrorCode.FRAME_UNEXPECTED_ERROR.code(), "invalid_frame_sequence");
    }

    protected void onSettings(SettingsFrame frame)
    {
        notifySettings(frame);
    }

    protected void onGoAway(GoAwayFrame frame)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("received {} on {}", frame, this);

        boolean failStreams = false;
        try (AutoLock ignored = lock.lock())
        {
            switch (closeState)
            {
                case NOT_CLOSED:
                {
                    goAwayRecv = frame;
                    if (frame.isGraceful())
                    {
                        closeState = CloseState.REMOTELY_CLOSED;
                        if (LOG.isDebugEnabled())
                            LOG.debug("waiting non-graceful GOAWAY on {}", this);
                    }
                    else
                    {
                        goAwaySent = newGoAwayFrame(false);
                        closeState = CloseState.CLOSING;
                        GoAwayFrame goAwayFrame = goAwaySent;
                        zeroStreamsAction = () -> writeControlFrame(goAwayFrame, Callback.from(this::terminate));
                        failStreams = true;
                    }
                    break;
                }
                case LOCALLY_CLOSED:
                {
                    goAwayRecv = frame;
                    if (frame.isGraceful())
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("waiting non-graceful GOAWAY on {}", this);
                    }
                    else
                    {
                        closeState = CloseState.CLOSING;
                        if (goAwaySent.isGraceful())
                        {
                            goAwaySent = newGoAwayFrame(false);
                            GoAwayFrame goAwayFrame = goAwaySent;
                            zeroStreamsAction = () -> writeControlFrame(goAwayFrame, Callback.from(() ->
                                terminateAndDisconnect(HTTP3ErrorCode.NO_ERROR.code(), "go_away", Promise.Invocable.noop())
                            ));
                        }
                        else
                        {
                            zeroStreamsAction = () -> terminateAndDisconnect(HTTP3ErrorCode.NO_ERROR.code(), "go_away", Promise.Invocable.noop());
                            failStreams = true;
                        }
                    }
                    break;
                }
                case REMOTELY_CLOSED:
                {
                    if (frame.isGraceful())
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("already received {} on {}", goAwayRecv, this);
                    }
                    else
                    {
                        goAwayRecv = frame;
                        closeState = CloseState.CLOSING;
                        if (goAwaySent == null || goAwaySent.isGraceful())
                        {
                            goAwaySent = newGoAwayFrame(false);
                            GoAwayFrame goAwayFrame = goAwaySent;
                            zeroStreamsAction = () -> writeControlFrame(goAwayFrame, Callback.from(this::terminate));
                        }
                        else
                        {
                            zeroStreamsAction = this::terminate;
                        }
                        failStreams = true;
                    }
                    break;
                }
                case CLOSING:
                case CLOSED:
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("already closed on {}", this);
                    break;
                }
                default:
                {
                    throw new IllegalStateException();
                }
            }
        }

        notifyGoAway(frame);

        if (failStreams)
        {
            // The other peer sent us a GOAWAY with the last processed streamId,
            // so we must fail the streams that have a bigger streamId.
            Predicate<HTTP3Stream> predicate = stream -> stream.isLocal() && stream.getId() > frame.getLastId();
            failStreams(predicate, HTTP3ErrorCode.REQUEST_CANCELLED_ERROR.code(), new RetryableStreamException());
        }

        tryRunZeroStreamsAction();
    }

    public void onStreamFailure(long streamId, long error, Throwable failure)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("stream failure 0x{}/{} for stream #{} on {}", Long.toHexString(error), failure.getMessage(), streamId, this);
        HTTP3Stream stream = getStream(streamId);
        if (stream != null)
        {
            stream.onFailure(error, failure);
        }
        else
        {
            StreamEndPoint endPoint = session.getStreamEndPoint(streamId);
            if (endPoint != null)
                endPoint.disconnect(error, failure, true, Promise.Invocable.noop());
        }
    }

    public void onSessionFailure(long error, String reason, Throwable failure)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("session failure 0x{}/{} on {}", Long.toHexString(error), failure.getMessage(), this);
        notifyFailure(error, reason, failure);
        close(error, reason, Promise.Invocable.noop());
    }

    public boolean onIdleTimeout(TimeoutException timeout)
    {
        boolean notify = false;
        boolean terminate = false;
        try (AutoLock ignored = lock.lock())
        {
            switch (closeState)
            {
                case NOT_CLOSED -> notify = true;
                case CLOSING, CLOSED -> terminate = true;
            }
        }

        if (terminate)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("already closed, ignored idle timeout for {}", this);
            terminateAndDisconnect(HTTP3ErrorCode.NO_ERROR.code(), "idle_timeout", Promise.Invocable.noop());
            return false;
        }

        boolean confirmed = true;
        if (notify)
            confirmed = notifyIdleTimeout();

        if (LOG.isDebugEnabled())
            LOG.debug("idle timeout {} for {}", confirmed ? "confirmed" : "ignored", this);

        if (!confirmed)
            return false;

        close(HTTP3ErrorCode.NO_ERROR.code(), "idle_timeout", Promise.Invocable.noop());

        return false;
    }

    /**
     * <p>Called when an external event wants to initiate the close of this session locally,
     * for example a close at the network level (due to e.g. stopping a component) or a timeout.</p>
     * <p>The correspondent passive event, where it's the remote peer that initiates the close,
     * is delivered via {@link #onClose(long, String)}.</p>
     *
     * @param error the close error
     * @param reason the close reason
     * @param promise the {@link Promise.Invocable} that gets notified when the close is complete
     * @see #onClose(long, String)
     */
    public void close(long error, String reason, Promise.Invocable<Session> promise)
    {
        GoAwayFrame goAwayFrame = null;
        try (AutoLock ignored = lock.lock())
        {
            switch (closeState)
            {
                case NOT_CLOSED:
                case LOCALLY_CLOSED:
                case REMOTELY_CLOSED:
                case CLOSING:
                {
                    if (goAwaySent == null || goAwaySent.isGraceful())
                        goAwaySent = goAwayFrame = newGoAwayFrame(false);
                    closeState = CloseState.CLOSED;
                    break;
                }
                case CLOSED:
                {
                    promise.succeeded(this);
                    return;
                }
                default:
                {
                    promise.failed(new IllegalStateException());
                    return;
                }
            }
        }

        failStreams(stream -> true, error, new IOException(reason));

        if (goAwayFrame != null)
        {
            writeControlFrame(goAwayFrame, Callback.from(() -> terminateAndDisconnect(error, reason, promise)));
        }
        else
        {
            terminateAndDisconnect(error, reason, promise);
        }
    }

    private void terminateAndDisconnect(long error, String reason, Promise.Invocable<Session> promise)
    {
        terminate();
        disconnect(error, reason, promise);
    }

    /**
     * <p>Initiates an outward disconnection, then notifies
     * {@link Session.Listener#onDisconnect(Session, long, String)}
     * when the disconnection is complete.</p>
     *
     * @param error the close error
     * @param reason the close reason.
     * @param promise the {@link Promise.Invocable} that gets notified when the disconnect is complete
     * @see ProtocolSession#disconnect(ConnectionCloseFrame, Throwable, Promise.Invocable)
     */
    private void disconnect(long error, String reason, Promise.Invocable<Session> promise)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("disconnecting 0x{}/{} on {}", Long.toHexString(error), reason, this);

        // Since the disconnect() is called by the implementation, notify the application.
        Promise.Invocable<ProtocolSession> p = Promise.Invocable.toPromise(promise, ps -> this);
        getProtocolSession().disconnect(new ConnectionCloseFrame(error, reason), null, Promise.Invocable.from(p, () -> notifyDisconnect(error, reason)));
    }

    private void failStreams(Predicate<HTTP3Stream> predicate, long error, Throwable failure)
    {
        streams.values().stream()
            .filter(predicate)
            .forEach(stream -> stream.onFailure(error, failure));
    }

    /**
     * Terminates this session at the HTTP/3 level, and possibly notifies the shutdown callback.
     * Termination at the QUIC level may still be in progress.
     *
     * @see #onClose(long, String)
     * @see #close(long, String, Promise.Invocable)
     */
    private void terminate()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("terminating {}", this);
        streamTimeouts.destroy();
        // Notify the shutdown completable.
        CompletableFuture<Session> shutdown;
        try (AutoLock ignored = lock.lock())
        {
            shutdown = this.shutdown;
        }
        if (shutdown != null)
            shutdown.complete(null);
    }

    private void tryRunZeroStreamsAction()
    {
        Runnable action = null;
        try (AutoLock ignored = lock.lock())
        {
            long count = streamCount.get();
            if (count > 0)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("deferring closing action, {} pending streams on {}", count, this);
                return;
            }

            switch (closeState)
            {
                case LOCALLY_CLOSED:
                {
                    if (goAwaySent.isGraceful())
                    {
                        action = zeroStreamsAction;
                        zeroStreamsAction = null;
                    }
                    break;
                }
                case REMOTELY_CLOSED:
                {
                    if (goAwaySent != null && goAwaySent.isGraceful())
                    {
                        action = zeroStreamsAction;
                        zeroStreamsAction = null;
                    }
                    break;
                }
                case CLOSING:
                {
                    closeState = CloseState.CLOSED;
                    action = zeroStreamsAction;
                    zeroStreamsAction = null;
                    break;
                }
                case NOT_CLOSED:
                case CLOSED:
                {
                    break;
                }
                default:
                {
                    throw new IllegalStateException();
                }
            }
        }

        if (action != null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("executing zero streams action on {}", this);
            action.run();
        }
    }

    /**
     * <p>Called when the local peer receives a close initiated by the remote peer.</p>
     * <p>The correspondent active event, where it's the local peer that initiates the close,
     * it's delivered via {@link #close(long, String, Promise.Invocable)}.</p>
     *
     * @param error the close error
     * @param reason the close reason
     */
    public void onClose(long error, String reason)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("session closed remotely 0x{}/{} {}", Long.toHexString(error), reason, this);

        // A close at the QUIC level does not allow any
        // data to be sent, update the state and notify.
        boolean notifyFailure;
        try (AutoLock ignored = lock.lock())
        {
            notifyFailure = closeState == CloseState.NOT_CLOSED;
            closeState = CloseState.CLOSED;
            zeroStreamsAction = null;
        }

        // SPEC: must not send any QUIC frame, except CONNECTION_CLOSE.
        Throwable failure = new EofException(reason);
        failStreams(stream -> true, error, failure);

        if (notifyFailure)
            onSessionFailure(error, reason, failure);

        // Nothing more inwards to propagate the
        // close to, start propagating outwards.
        disconnect(error, reason, Promise.Invocable.noop());
    }

    private Map<Long, Long> notifyPreface()
    {
        Session.Listener listener = getListener();
        try
        {
            if (listener != null)
                return listener.onPreface(this);
            return null;
        }
        catch (Throwable x)
        {
            LOG.info("failure notifying listener {}", listener, x);
            return null;
        }
    }

    private void notifySettings(SettingsFrame frame)
    {
        Session.Listener listener = getListener();
        try
        {
            if (listener != null)
                listener.onSettings(this, frame);
        }
        catch (Throwable x)
        {
            LOG.info("failure notifying listener {}", listener, x);
        }
    }

    private void notifyGoAway(GoAwayFrame frame)
    {
        Session.Listener listener = getListener();
        try
        {
            if (listener != null)
                listener.onGoAway(this, frame);
        }
        catch (Throwable x)
        {
            LOG.info("failure notifying listener {}", listener, x);
        }
    }

    private boolean notifyIdleTimeout()
    {
        Session.Listener listener = getListener();
        try
        {
            if (listener != null)
                return listener.onIdleTimeout(this);
            return true;
        }
        catch (Throwable x)
        {
            LOG.info("failure notifying listener {}", listener, x);
            return true;
        }
    }

    private void notifyDisconnect(long error, String reason)
    {
        Session.Listener listener = getListener();
        try
        {
            if (listener != null)
                listener.onDisconnect(this, error, reason);
        }
        catch (Throwable x)
        {
            LOG.info("failure notifying listener {}", listener, x);
        }
    }

    private void notifyFailure(long error, String reason, Throwable failure)
    {
        Session.Listener listener = getListener();
        try
        {
            if (listener != null)
                listener.onFailure(this, error, reason, failure);
        }
        catch (Throwable x)
        {
            LOG.info("failure notifying listener {}", listener, x);
        }
    }

    public boolean isClosed()
    {
        return closeState == CloseState.CLOSED;
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        dumpObjects(out, indent, new DumpableCollection("streams", getStreams()));
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x[streams=%d,%s]", TypeUtil.toShortName(getClass()), hashCode(), streamCount.get(), closeState);
    }

    /**
     * <p>Processes the HTTP/3 parser events.</p>
     * <p>Control frames (such as GOAWAY) arrive on the unidirectional control stream,
     * while message frames (such as HEADERS and DATA) arrive on bidirectional streams.
     * This class offers a chance or serializing the frame processing, if necessary;
     * this depends on the logic in {@link StreamEndPoint}.</p>
     */
    private class FrameListener implements ParserListener
    {
        @Override
        public void onHeaders(long streamId, HeadersFrame frame, boolean wasBlocked)
        {
            HTTP3Session.this.onHeaders(streamId, frame, wasBlocked);
        }

        @Override
        public void onData(long streamId, DataFrame frame)
        {
            HTTP3Session.this.onData(streamId, frame);
        }

        @Override
        public void onSettings(SettingsFrame frame)
        {
            HTTP3Session.this.onSettings(frame);
        }

        @Override
        public void onGoAway(GoAwayFrame frame)
        {
            HTTP3Session.this.onGoAway(frame);
        }

        @Override
        public void onStreamFailure(long streamId, long error, Throwable failure)
        {
            HTTP3Session.this.onStreamFailure(streamId, error, failure);
        }

        @Override
        public void onSessionFailure(long error, String reason, Throwable failure)
        {
            HTTP3Session.this.onSessionFailure(error, reason, failure);
        }
    }

    private enum CloseState
    {
        NOT_CLOSED, LOCALLY_CLOSED, REMOTELY_CLOSED, CLOSING, CLOSED
    }

    private class StreamTimeouts extends CyclicTimeouts<HTTP3Stream>
    {
        private StreamTimeouts(Scheduler scheduler)
        {
            super(scheduler);
        }

        @Override
        protected Iterator<HTTP3Stream> iterator()
        {
            return streams.values().stream()
                .filter(stream -> stream.getIdleTimeout() > 0)
                .iterator();
        }

        @Override
        protected boolean onExpired(HTTP3Stream stream)
        {
            TimeoutException timeout = new TimeoutException("idle timeout " + stream.getIdleTimeout() + " ms elapsed");
            stream.onIdleTimeout(timeout, Promise.from(timedOut ->
            {
                if (timedOut)
                    disconnect(stream, timeout);
            }, x -> disconnect(stream, x)));
            // The iterator returned from the method above does not support removal.
            return false;
        }

        private void disconnect(HTTP3Stream stream, Throwable failure)
        {
            stream.disconnect(HTTP3ErrorCode.REQUEST_CANCELLED_ERROR.code(), failure, Promise.Invocable.noop());
        }
    }
}
