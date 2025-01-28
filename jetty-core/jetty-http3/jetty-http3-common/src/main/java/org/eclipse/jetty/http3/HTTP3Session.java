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
import java.util.function.Consumer;
import java.util.function.Function;
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
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class HTTP3Session extends ContainerLifeCycle implements Session, ParserListener
{
    private static final Logger LOG = LoggerFactory.getLogger(HTTP3Session.class);

    private final AutoLock lock = new AutoLock();
    private final AtomicLong lastStreamId = new AtomicLong(0);
    private final Map<Long, HTTP3Stream> streams = new ConcurrentHashMap<>();
    private final ProtocolSession session;
    private final Session.Listener listener;
    private final AtomicInteger streamCount = new AtomicInteger();
    private final StreamTimeouts streamTimeouts;
    private long streamIdleTimeout;
    private CloseState closeState = CloseState.CLOSED;
    private GoAwayFrame goAwaySent;
    private GoAwayFrame goAwayRecv;
    private Runnable zeroStreamsAction;
    private CompletableFuture<Void> shutdown;

    public HTTP3Session(Scheduler scheduler, ProtocolSession session, Session.Listener listener)
    {
        this.session = session;
        this.listener = listener;
        this.streamTimeouts = new StreamTimeouts(scheduler);
    }

    public ProtocolSession getProtocolSession()
    {
        return session;
    }

    public Session.Listener getListener()
    {
        return listener;
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
        return session.getSession().getLocalBidirectionalMaxStreams();
    }

    @Override
    public CompletableFuture<Void> goAway(boolean graceful)
    {
        return goAway(newGoAwayFrame(graceful));
    }

    private CompletableFuture<Void> goAway(GoAwayFrame frame)
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
                        zeroStreamsAction = () -> goAway(false);
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
                        zeroStreamsAction = () -> goAway(false);
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
            Callback.Completable result = new Callback.Completable();
            writeControlFrame(frame, result);
            return result.whenComplete((r, x) -> tryRunZeroStreamsAction());
        }
        else
        {
            if (failStreams)
            {
                long error = HTTP3ErrorCode.REQUEST_CANCELLED_ERROR.code();
                failStreams(stream -> true, error, new ClosedChannelException());
                return terminateAndDisconnect(error, "go_away");
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    protected GoAwayFrame newGoAwayFrame(boolean graceful)
    {
        return new GoAwayFrame(lastStreamId.get());
    }

    public CompletableFuture<Void> shutdown()
    {
        CompletableFuture<Void> result;
        try (AutoLock ignored = lock.lock())
        {
            if (shutdown != null)
                return shutdown;
            shutdown = result = new Callback.Completable();
        }
        goAway(true);
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

    protected HTTP3Stream createStream(StreamEndPoint endPoint, Consumer<Throwable> fail)
    {
        long streamId = endPoint.getStream().getId();
        return streams.compute(streamId, (id, stream) ->
        {
            if (stream != null)
                throw new IllegalStateException("duplicate stream id " + streamId);
            return newHTTP3Stream(endPoint, fail, true);
        });
    }

    protected HTTP3Stream getOrCreateStream(StreamEndPoint endPoint)
    {
        if (endPoint == null)
            return null;
        return streams.computeIfAbsent(endPoint.getStream().getId(), id -> newHTTP3Stream(endPoint, null, false));
    }

    private HTTP3Stream newHTTP3Stream(StreamEndPoint endPoint, Consumer<Throwable> fail, boolean local)
    {
        Throwable failure = null;
        try (AutoLock ignored = lock.lock())
        {
            if (closeState == CloseState.NOT_CLOSED)
                streamCount.incrementAndGet();
            else
                failure = new IllegalStateException("session_closed");
        }

        if (failure == null)
        {
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
        else
        {
            if (LOG.isDebugEnabled())
                LOG.debug("could not create stream for {} on {}", endPoint, this);
            if (fail != null)
                fail.accept(failure);
            return null;
        }
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

    private Map<Long, Long> notifyPreface()
    {
        try
        {
            return listener.onPreface(this);
        }
        catch (Throwable x)
        {
            LOG.info("failure notifying listener {}", listener, x);
            return null;
        }
    }

    @Override
    public void onSettings(SettingsFrame frame)
    {
        notifySettings(frame);
    }

    private void notifySettings(SettingsFrame frame)
    {
        try
        {
            listener.onSettings(this, frame);
        }
        catch (Throwable x)
        {
            LOG.info("failure notifying listener {}", listener, x);
        }
    }

    private void notifyGoAway(GoAwayFrame frame)
    {
        try
        {
            listener.onGoAway(this, frame);
        }
        catch (Throwable x)
        {
            LOG.info("failure notifying listener {}", listener, x);
        }
    }

    private boolean notifyIdleTimeout()
    {
        try
        {
            return listener.onIdleTimeout(this);
        }
        catch (Throwable x)
        {
            LOG.info("failure notifying listener {}", listener, x);
            return true;
        }
    }

    @Override
    public void onHeaders(long streamId, HeadersFrame frame, boolean wasBlocked)
    {
        MetaData metaData = frame.getMetaData();
        if (metaData.isRequest() || metaData.isResponse())
        {
            throw new IllegalStateException("invalid metadata");
        }
        else
        {
            StreamEndPoint endPoint = session.getStreamEndPoint(streamId);
            HTTP3Stream stream = getOrCreateStream(endPoint);
            if (LOG.isDebugEnabled())
                LOG.debug("received trailer {} on {}", frame, stream);
            if (stream != null)
                stream.onTrailer(frame);
        }
    }

    @Override
    public void onData(long streamId, DataFrame frame)
    {
        HTTP3Stream stream = getStream(streamId);
        if (LOG.isDebugEnabled())
            LOG.debug("received {} on {}", frame, stream);
        if (stream != null)
            stream.onData(frame);
        else
            onSessionFailure(HTTP3ErrorCode.FRAME_UNEXPECTED_ERROR.code(), "invalid_frame_sequence", new IllegalStateException("invalid frame sequence"));
    }

    @Override
    public void onGoAway(GoAwayFrame frame)
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
                                terminateAndDisconnect(HTTP3ErrorCode.NO_ERROR.code(), "go_away")
                            ));
                        }
                        else
                        {
                            zeroStreamsAction = () -> terminateAndDisconnect(HTTP3ErrorCode.NO_ERROR.code(), "go_away");
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
            failStreams(predicate, HTTP3ErrorCode.REQUEST_CANCELLED_ERROR.code(), new EofException());
        }

        tryRunZeroStreamsAction();
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
            terminateAndDisconnect(HTTP3ErrorCode.NO_ERROR.code(), "idle_timeout");
            return false;
        }

        boolean confirmed = true;
        if (notify)
            confirmed = notifyIdleTimeout();

        if (LOG.isDebugEnabled())
            LOG.debug("idle timeout {} for {}", confirmed ? "confirmed" : "ignored", this);

        if (!confirmed)
            return false;

        close(HTTP3ErrorCode.NO_ERROR.code(), "idle_timeout");

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
     * @see #onClose(long, String)
     */
    public CompletableFuture<Void> close(long error, String reason)
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
                    return CompletableFuture.completedFuture(null);
                }
                default:
                {
                    return CompletableFuture.failedFuture(new IllegalStateException());
                }
            }
        }

        failStreams(stream -> true, error, new IOException(reason));

        if (goAwayFrame != null)
        {
            Callback.Completable completable = new Callback.Completable();
            writeControlFrame(goAwayFrame, completable);
            return completable.handle((r, x) -> terminateAndDisconnect(error, reason))
                .thenCompose(Function.identity());
        }
        else
        {
            return terminateAndDisconnect(error, reason);
        }
    }

    private CompletableFuture<Void> terminateAndDisconnect(long error, String reason)
    {
        terminate();
        return disconnect(error, reason);
    }

    /**
     * <p>Initiates an outward disconnection, then notifies
     * {@link Session.Listener#onDisconnect(Session, long, String)}
     * when the disconnection is complete.</p>
     *
     * @param error the close error
     * @param reason the close reason.
     * @see ProtocolSession#disconnect(ConnectionCloseFrame, Throwable)
     */
    private CompletableFuture<Void> disconnect(long error, String reason)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("disconnecting 0x{}/{} on {}", Long.toHexString(error), reason, this);
        return getProtocolSession().disconnect(new ConnectionCloseFrame(error, reason), null)
            // Since the disconnect() above is called by
            // the implementation, notify the application.
            .whenComplete((r, x) -> notifyDisconnect(error, reason));
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
     * @see #close(long, String)
     */
    private void terminate()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("terminating {}", this);
        streamTimeouts.destroy();
        // Notify the shutdown completable.
        CompletableFuture<Void> shutdown;
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
     * it's delivered via {@link #close(long, String)}.</p>
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
        disconnect(error, reason);
    }

    private void notifyDisconnect(long error, String reason)
    {
        try
        {
            listener.onDisconnect(this, error, reason);
        }
        catch (Throwable x)
        {
            LOG.info("failure notifying listener {}", listener, x);
        }
    }

    @Override
    public void onStreamFailure(long streamId, long error, Throwable failure)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("stream failure 0x{}/{} for stream #{} on {}", Long.toHexString(error), failure.getMessage(), streamId, this);
        HTTP3Stream stream = getStream(streamId);
        if (stream != null)
            stream.onFailure(error, failure);
    }

    @Override
    public void onSessionFailure(long error, String reason, Throwable failure)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("session failure 0x{}/{} on {}", Long.toHexString(error), failure.getMessage(), this);
        notifyFailure(error, reason, failure);
        close(error, reason);
    }

    private void notifyFailure(long error, String reason, Throwable failure)
    {
        try
        {
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
        return String.format("%s@%x[streams=%d,%s]", getClass().getSimpleName(), hashCode(), streamCount.get(), closeState);
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
            stream.disconnect(HTTP3ErrorCode.REQUEST_CANCELLED_ERROR.code(), failure);
        }
    }
}
