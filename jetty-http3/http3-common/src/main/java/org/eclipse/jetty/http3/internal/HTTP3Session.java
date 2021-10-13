//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http3.internal;

import java.io.IOException;
import java.net.SocketAddress;
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
import java.util.function.Predicate;

import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.frames.DataFrame;
import org.eclipse.jetty.http3.frames.Frame;
import org.eclipse.jetty.http3.frames.GoAwayFrame;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.frames.SettingsFrame;
import org.eclipse.jetty.http3.internal.parser.ParserListener;
import org.eclipse.jetty.io.CyclicTimeouts;
import org.eclipse.jetty.quic.common.ProtocolSession;
import org.eclipse.jetty.quic.common.QuicStreamEndPoint;
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
    private final AtomicLong lastId = new AtomicLong();
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

    public HTTP3Session(ProtocolSession session, Session.Listener listener)
    {
        this.session = session;
        this.listener = listener;
        this.streamTimeouts = new StreamTimeouts(session.getQuicSession().getScheduler());
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
        return getProtocolSession().getQuicSession().getLocalAddress();
    }

    @Override
    public SocketAddress getRemoteSocketAddress()
    {
        return getProtocolSession().getQuicSession().getRemoteAddress();
    }

    @Override
    public Collection<Stream> getStreams()
    {
        return List.copyOf(streams.values());
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
        try (AutoLock l = lock.lock())
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
                            zeroStreamsAction = () -> terminate("go_away");
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
            result.thenRun(this::tryRunZeroStreamsAction);
            writeControlFrame(frame, result);
            return result;
        }
        else
        {
            if (failStreams)
                failStreams(stream -> true, "go_away", true);
            return CompletableFuture.completedFuture(null);
        }
    }

    protected GoAwayFrame newGoAwayFrame(boolean graceful)
    {
        return new GoAwayFrame(lastId.get());
    }

    public CompletableFuture<Void> shutdown()
    {
        CompletableFuture<Void> result;
        try (AutoLock l = lock.lock())
        {
            if (shutdown != null)
                return shutdown;
            shutdown = result = new Callback.Completable();
        }
        goAway(true);
        return result;
    }

    protected void updateLastId(long id)
    {
        Atomics.updateMax(lastId, id);
    }

    public void outwardClose(long error, String reason)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("outward closing 0x{}/{} on {}", Long.toHexString(error), reason, this);
        getProtocolSession().outwardClose(error, reason);
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

    protected CompletableFuture<Stream> newRequest(long streamId, HeadersFrame frame, Stream.Listener listener)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("new request stream #{} with {} on {}", streamId, frame, this);

        QuicStreamEndPoint endPoint = session.getOrCreateStreamEndPoint(streamId, session::configureProtocolEndPoint);

        Promise.Completable<Stream> promise = new Promise.Completable<>();
        promise.whenComplete((s, x) ->
        {
            if (x != null)
                endPoint.close(HTTP3ErrorCode.REQUEST_CANCELLED_ERROR.code(), x);
        });
        HTTP3Stream stream = createStream(endPoint, promise::failed);
        if (stream == null)
            return promise;

        stream.setListener(listener);

        stream.writeFrame(frame)
            .whenComplete((r, x) ->
            {
                if (x == null)
                {
                    if (listener == null)
                        endPoint.shutdownInput(HTTP3ErrorCode.NO_ERROR.code());
                    promise.succeeded(stream);
                }
                else
                {
                    removeStream(stream);
                    promise.failed(x);
                }
            });
        stream.updateClose(frame.isLast(), true);

        return promise;
    }

    protected HTTP3Stream createStream(QuicStreamEndPoint endPoint, Consumer<Throwable> fail)
    {
        long streamId = endPoint.getStreamId();
        return streams.compute(streamId, (id, stream) ->
        {
            if (stream != null)
                throw new IllegalStateException("duplicate stream id " + streamId);
            return newHTTP3Stream(endPoint, fail, true);
        });
    }

    protected HTTP3Stream getOrCreateStream(QuicStreamEndPoint endPoint)
    {
        if (endPoint == null)
            return null;
        return streams.computeIfAbsent(endPoint.getStreamId(), id -> newHTTP3Stream(endPoint, null, false));
    }

    private HTTP3Stream newHTTP3Stream(QuicStreamEndPoint endPoint, Consumer<Throwable> fail, boolean local)
    {
        Throwable failure = null;
        try (AutoLock l = lock.lock())
        {
            if (closeState == CloseState.NOT_CLOSED)
                streamCount.incrementAndGet();
            else
                failure = new IllegalStateException("session_closed");
        }

        if (failure == null)
        {
            HTTP3Stream stream = new HTTP3Stream(this, endPoint, local);
            long idleTimeout = getStreamIdleTimeout();
            if (idleTimeout > 0)
                stream.setIdleTimeout(idleTimeout);
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

    protected HTTP3Stream getStream(long streamId)
    {
        return streams.get(streamId);
    }

    public void removeStream(HTTP3Stream stream)
    {
        boolean removed = streams.remove(stream.getId()) != null;
        if (removed)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("destroyed {}", stream);

            if (streamCount.decrementAndGet() == 0)
                tryRunZeroStreamsAction();
        }
    }

    public abstract void writeControlFrame(Frame frame, Callback callback);

    public abstract void writeMessageFrame(long streamId, Frame frame, Callback callback);

    public Map<Long, Long> onPreface()
    {
        Map<Long, Long> settings = notifyPreface();
        if (LOG.isDebugEnabled())
            LOG.debug("produced settings {} on {}", settings, this);
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
        if (LOG.isDebugEnabled())
            LOG.debug("received {} on {}", frame, this);
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
    public void onHeaders(long streamId, HeadersFrame frame)
    {
        MetaData metaData = frame.getMetaData();
        if (metaData.isRequest() || metaData.isResponse())
        {
            throw new IllegalStateException("invalid metadata");
        }
        else
        {
            QuicStreamEndPoint endPoint = session.getStreamEndPoint(streamId);
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
            fail(HTTP3ErrorCode.FRAME_UNEXPECTED_ERROR.code(), "invalid_frame_sequence");
    }

    public void onDataAvailable(long streamId)
    {
        HTTP3Stream stream = getStream(streamId);
        if (LOG.isDebugEnabled())
            LOG.debug("notifying data available on {}", stream);
        stream.onDataAvailable();
    }

    void fail(long error, String reason)
    {
        // Hard failure, no need to send a GOAWAY.
        try (AutoLock l = lock.lock())
        {
            closeState = CloseState.CLOSED;
        }
        outwardClose(error, reason);
        notifyFailure(new IOException(String.format("%d/%s", error, reason)));
    }

    @Override
    public void onGoAway(GoAwayFrame frame)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("received {} on {}", frame, this);

        boolean failStreams = false;
        try (AutoLock l = lock.lock())
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
                        zeroStreamsAction = () -> writeControlFrame(goAwayFrame, Callback.from(() -> terminate("go_away")));
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
                            zeroStreamsAction = () -> writeControlFrame(goAwayFrame, Callback.from(() -> terminate("go_away")));
                        }
                        else
                        {
                            zeroStreamsAction = () -> terminate("go_away");
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
                            zeroStreamsAction = () -> writeControlFrame(goAwayFrame, Callback.from(() -> terminate("go_away")));
                        }
                        else
                        {
                            zeroStreamsAction = () -> terminate("go_away");
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
            failStreams(predicate, "go_away", true);
        }

        tryRunZeroStreamsAction();
    }

    public boolean onIdleTimeout()
    {
        boolean notify = false;
        try (AutoLock l = lock.lock())
        {
            switch (closeState)
            {
                case NOT_CLOSED:
                {
                    notify = true;
                    break;
                }
                case LOCALLY_CLOSED:
                case REMOTELY_CLOSED:
                {
                    break;
                }
                case CLOSING:
                case CLOSED:
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("already closed, ignored idle timeout for {}", this);
                    return false;
                }
                default:
                {
                    throw new IllegalStateException();
                }
            }
        }

        boolean confirmed = true;
        if (notify)
            confirmed = notifyIdleTimeout();

        if (LOG.isDebugEnabled())
            LOG.debug("idle timeout {} for {}", confirmed ? "confirmed" : "ignored", this);

        if (!confirmed)
            return false;

        disconnect("idle_timeout");

        return false;
    }

    public void disconnect(String reason)
    {
        GoAwayFrame goAwayFrame = null;
        try (AutoLock l = lock.lock())
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
                    return;
                }
                default:
                {
                    throw new IllegalStateException();
                }
            }
        }

        failStreams(stream -> true, reason, true);

        if (goAwayFrame != null)
            writeControlFrame(goAwayFrame, Callback.from(() -> terminate(reason)));
        else
            terminate(reason);
    }

    private void failStreams(Predicate<HTTP3Stream> predicate, String reason, boolean close)
    {
        long error = HTTP3ErrorCode.REQUEST_CANCELLED_ERROR.code();
        Throwable failure = new IOException(reason);
        streams.values().stream()
            .filter(predicate)
            .forEach(stream ->
            {
                if (close)
                    stream.reset(error, failure);
                // Since the stream failure was generated
                // by a GOAWAY, notify the application.
                stream.onFailure(failure);
            });
    }

    private void terminate(String reason)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("terminating reason={} for {}", reason, this);
        streamTimeouts.destroy();
        outwardClose(HTTP3ErrorCode.NO_ERROR.code(), reason);
        // Since the close() above is called by the
        // implementation, notify the application.
        notifyDisconnect();
        // Notify the shutdown completable.
        CompletableFuture<Void> shutdown;
        try (AutoLock l = lock.lock())
        {
            shutdown = this.shutdown;
        }
        if (shutdown != null)
            shutdown.complete(null);
    }

    private void tryRunZeroStreamsAction()
    {
        Runnable action = null;
        try (AutoLock l = lock.lock())
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

    public void onClose(long error, String reason)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("session closed remotely 0x{}/{} {}", Long.toHexString(error), reason, this);

        // A close at the QUIC level does not allow any
        // data to be sent, update the state and notify.
        boolean notifyFailure;
        try (AutoLock l = lock.lock())
        {
            notifyFailure = closeState == CloseState.NOT_CLOSED;
            closeState = CloseState.CLOSED;
            zeroStreamsAction = null;
            // TODO: what about field shutdown?
        }

        // No point in closing the streams, as QUIC frames cannot be sent.
        failStreams(stream -> true, "remote_close", false);

        if (notifyFailure)
            fail(error, reason);

        notifyDisconnect();
    }

    private void notifyDisconnect()
    {
        try
        {
            listener.onDisconnect(this);
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
        {
            stream.onFailure(failure);
            removeStream(stream);
        }
    }

    @Override
    public void onSessionFailure(long error, String reason)
    {
        // TODO
    }

    public void notifyFailure(Throwable failure)
    {
        try
        {
            listener.onFailure(this, failure);
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
            if (stream.onIdleTimeout(new TimeoutException("idle timeout " + stream.getIdleTimeout() + " ms elapsed")))
                removeStream(stream);
            // The iterator returned from the method above does not support removal.
            return false;
        }
    }
}
