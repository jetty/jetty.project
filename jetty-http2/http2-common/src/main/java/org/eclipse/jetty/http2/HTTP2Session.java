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

package org.eclipse.jetty.http2;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.DisconnectFrame;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.FrameType;
import org.eclipse.jetty.http2.frames.GoAwayFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PingFrame;
import org.eclipse.jetty.http2.frames.PriorityFrame;
import org.eclipse.jetty.http2.frames.PushPromiseFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.http2.frames.WindowUpdateFrame;
import org.eclipse.jetty.http2.generator.Generator;
import org.eclipse.jetty.http2.parser.Parser;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Atomics;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.CountingCallback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Scheduler;

public abstract class HTTP2Session implements ISession, Parser.Listener
{
    private static final Logger LOG = Log.getLogger(HTTP2Session.class);

    private final ConcurrentMap<Integer, IStream> streams = new ConcurrentHashMap<>();
    private final AtomicInteger streamIds = new AtomicInteger();
    private final AtomicInteger lastStreamId = new AtomicInteger();
    private final AtomicInteger localStreamCount = new AtomicInteger();
    private final AtomicInteger remoteStreamCount = new AtomicInteger();
    private final AtomicInteger sendWindow = new AtomicInteger();
    private final AtomicInteger recvWindow = new AtomicInteger();
    private final AtomicReference<CloseState> closed = new AtomicReference<>(CloseState.NOT_CLOSED);
    private final Scheduler scheduler;
    private final EndPoint endPoint;
    private final Generator generator;
    private final Listener listener;
    private final FlowControlStrategy flowControl;
    private final HTTP2Flusher flusher;
    private int maxLocalStreams;
    private int maxRemoteStreams;
    private long streamIdleTimeout;
    private boolean pushEnabled;

    public HTTP2Session(Scheduler scheduler, EndPoint endPoint, Generator generator, Listener listener, FlowControlStrategy flowControl, int initialStreamId)
    {
        this.scheduler = scheduler;
        this.endPoint = endPoint;
        this.generator = generator;
        this.listener = listener;
        this.flowControl = flowControl;
        this.flusher = new HTTP2Flusher(this);
        this.maxLocalStreams = -1;
        this.maxRemoteStreams = -1;
        this.streamIds.set(initialStreamId);
        this.streamIdleTimeout = endPoint.getIdleTimeout();
        this.sendWindow.set(FlowControlStrategy.DEFAULT_WINDOW_SIZE);
        this.recvWindow.set(FlowControlStrategy.DEFAULT_WINDOW_SIZE);
        this.pushEnabled = true; // SPEC: by default, push is enabled.
    }

    public FlowControlStrategy getFlowControlStrategy()
    {
        return flowControl;
    }

    public int getMaxLocalStreams()
    {
        return maxLocalStreams;
    }

    public void setMaxLocalStreams(int maxLocalStreams)
    {
        this.maxLocalStreams = maxLocalStreams;
    }

    public int getMaxRemoteStreams()
    {
        return maxRemoteStreams;
    }

    public void setMaxRemoteStreams(int maxRemoteStreams)
    {
        this.maxRemoteStreams = maxRemoteStreams;
    }

    public long getStreamIdleTimeout()
    {
        return streamIdleTimeout;
    }

    public void setStreamIdleTimeout(long streamIdleTimeout)
    {
        this.streamIdleTimeout = streamIdleTimeout;
    }

    public EndPoint getEndPoint()
    {
        return endPoint;
    }

    public Generator getGenerator()
    {
        return generator;
    }

    @Override
    public void onData(final DataFrame frame)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Received {}", frame);

        int streamId = frame.getStreamId();
        final IStream stream = getStream(streamId);

        // SPEC: the session window must be updated even if the stream is null.
        // The flow control length includes the padding bytes.
        final int flowControlLength = frame.remaining() + frame.padding();
        flowControl.onDataReceived(this, stream, flowControlLength);

        if (stream != null)
        {
            if (getRecvWindow() < 0)
            {
                close(ErrorCode.FLOW_CONTROL_ERROR.code, "session_window_exceeded", Callback.NOOP);
            }
            else
            {
                stream.process(frame, new Callback()
                {
                    @Override
                    public void succeeded()
                    {
                        flowControl.onDataConsumed(HTTP2Session.this, stream, flowControlLength);
                    }

                    @Override
                    public void failed(Throwable x)
                    {
                        // Consume also in case of failures, to free the
                        // session flow control window for other streams.
                        flowControl.onDataConsumed(HTTP2Session.this, stream, flowControlLength);
                    }
                });
            }
        }
        else
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Ignoring {}, stream #{} not found", frame, streamId);
            // We must enlarge the session flow control window,
            // otherwise other requests will be stalled.
            flowControl.onDataConsumed(this, null, flowControlLength);
        }
    }

    @Override
    public abstract void onHeaders(HeadersFrame frame);

    @Override
    public void onPriority(PriorityFrame frame)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Received {}", frame);
    }

    @Override
    public void onReset(ResetFrame frame)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Received {}", frame);

        IStream stream = getStream(frame.getStreamId());
        if (stream != null)
            stream.process(frame, Callback.NOOP);
        else
            notifyReset(this, frame);
    }

    @Override
    public void onSettings(SettingsFrame frame)
    {
        // SPEC: SETTINGS frame MUST be replied.
        onSettings(frame, true);
    }

    public void onSettings(SettingsFrame frame, boolean reply)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Received {}", frame);

        if (frame.isReply())
            return;

        // Iterate over all settings
        for (Map.Entry<Integer, Integer> entry : frame.getSettings().entrySet())
        {
            int key = entry.getKey();
            int value = entry.getValue();
            switch (key)
            {
                case SettingsFrame.HEADER_TABLE_SIZE:
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Update HPACK header table size to {}", value);
                    generator.setHeaderTableSize(value);
                    break;
                }
                case SettingsFrame.ENABLE_PUSH:
                {
                    // SPEC: check the value is sane.
                    if (value != 0 && value != 1)
                    {
                        onConnectionFailure(ErrorCode.PROTOCOL_ERROR.code, "invalid_settings_enable_push");
                        return;
                    }
                    pushEnabled = value == 1;
                    break;
                }
                case SettingsFrame.MAX_CONCURRENT_STREAMS:
                {
                    maxLocalStreams = value;
                    if (LOG.isDebugEnabled())
                        LOG.debug("Update max local concurrent streams to {}", maxLocalStreams);
                    break;
                }
                case SettingsFrame.INITIAL_WINDOW_SIZE:
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Update initial window size to {}", value);
                    flowControl.updateInitialStreamWindow(this, value, false);
                    break;
                }
                case SettingsFrame.MAX_FRAME_SIZE:
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Update max frame size to {}", value);
                    // SPEC: check the max frame size is sane.
                    if (value < Frame.DEFAULT_MAX_LENGTH || value > Frame.MAX_MAX_LENGTH)
                    {
                        onConnectionFailure(ErrorCode.PROTOCOL_ERROR.code, "invalid_settings_max_frame_size");
                        return;
                    }
                    generator.setMaxFrameSize(value);
                    break;
                }
                case SettingsFrame.MAX_HEADER_LIST_SIZE:
                {
                    // TODO implement
                    LOG.warn("NOT IMPLEMENTED max header list size to {}", value);
                    break;
                }
                default:
                {
                    LOG.debug("Unknown setting {}:{}", key, value);
                    break;
                }
            }
        }
        notifySettings(this, frame);

        if (reply)
        {
            SettingsFrame replyFrame = new SettingsFrame(Collections.<Integer, Integer>emptyMap(), true);
            settings(replyFrame, Callback.NOOP);
        }
    }

    @Override
    public void onPing(PingFrame frame)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Received {}", frame);

        if (frame.isReply())
        {
            notifyPing(this, frame);
        }
        else
        {
            PingFrame reply = new PingFrame(frame.getPayload(), true);
            control(null, Callback.NOOP, reply);
        }
    }

    /**
     * This method is called when receiving a GO_AWAY from the other peer.
     * We check the close state to act appropriately:
     *
     * * NOT_CLOSED: we move to REMOTELY_CLOSED and queue a disconnect, so
     *   that the content of the queue is written, and then the connection
     *   closed. We notify the application after being terminated.
     *   See <code>HTTP2Session.ControlEntry#succeeded()</code>
     *
     * * In all other cases, we do nothing since other methods are already
     *   performing their actions.
     *
     * @param frame the GO_AWAY frame that has been received.
     * @see #close(int, String, Callback)
     * @see #onShutdown()
     * @see #onIdleTimeout()
     */
    @Override
    public void onGoAway(final GoAwayFrame frame)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Received {}", frame);

        while (true)
        {
            CloseState current = closed.get();
            switch (current)
            {
                case NOT_CLOSED:
                {
                    if (closed.compareAndSet(current, CloseState.REMOTELY_CLOSED))
                    {
                        // We received a GO_AWAY, so try to write
                        // what's in the queue and then disconnect.
                        control(null, new Callback()
                        {
                            @Override
                            public void succeeded()
                            {
                                notifyClose(HTTP2Session.this, frame);
                            }

                            @Override
                            public void failed(Throwable x)
                            {
                                notifyClose(HTTP2Session.this, frame);
                            }
                        }, new DisconnectFrame());
                        return;
                    }
                    break;
                }
                default:
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Ignored {}, already closed", frame);
                    return;
                }
            }
        }
    }

    @Override
    public void onWindowUpdate(WindowUpdateFrame frame)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Received {}", frame);

        int streamId = frame.getStreamId();
        if (streamId > 0)
        {
            IStream stream = getStream(streamId);
            if (stream != null)
                onWindowUpdate(stream, frame);
        }
        else
        {
            onWindowUpdate(null, frame);
        }
    }

    @Override
    public void onConnectionFailure(int error, String reason)
    {
        close(error, reason, Callback.NOOP);
        notifyFailure(this, new IOException(String.format("%d/%s", error, reason)));
    }

    @Override
    public void newStream(HeadersFrame frame, Promise<Stream> promise, Stream.Listener listener)
    {
        // Synchronization is necessary to atomically create
        // the stream id and enqueue the frame to be sent.
        boolean queued;
        synchronized (this)
        {
            int streamId = frame.getStreamId();
            if (streamId <= 0)
            {
                streamId = streamIds.getAndAdd(2);
                PriorityFrame priority = frame.getPriority();
                priority = priority == null ? null : new PriorityFrame(streamId, priority.getParentStreamId(),
                        priority.getWeight(), priority.isExclusive());
                frame = new HeadersFrame(streamId, frame.getMetaData(), priority, frame.isEndStream());
            }
            final IStream stream = createLocalStream(streamId, promise);
            if (stream == null)
                return;
            stream.setListener(listener);

            ControlEntry entry = new ControlEntry(frame, stream, new PromiseCallback<>(promise, stream));
            queued = flusher.append(entry);
        }
        // Iterate outside the synchronized block.
        if (queued)
            flusher.iterate();
    }

    @Override
    public int priority(PriorityFrame frame, Callback callback)
    {
        int streamId = frame.getStreamId();
        IStream stream = streams.get(streamId);
        if (stream == null)
        {
            streamId = streamIds.getAndAdd(2);
            frame = new PriorityFrame(streamId, frame.getParentStreamId(),
                    frame.getWeight(), frame.isExclusive());
        }
        control(stream, callback, frame);
        return streamId;
    }

    @Override
    public void push(IStream stream, Promise<Stream> promise, PushPromiseFrame frame, Stream.Listener listener)
    {
        // Synchronization is necessary to atomically create
        // the stream id and enqueue the frame to be sent.
        boolean queued;
        synchronized (this)
        {
            int streamId = streamIds.getAndAdd(2);
            frame = new PushPromiseFrame(frame.getStreamId(), streamId, frame.getMetaData());

            final IStream pushStream = createLocalStream(streamId, promise);
            if (pushStream == null)
                return;
            pushStream.setListener(listener);

            ControlEntry entry = new ControlEntry(frame, pushStream, new PromiseCallback<>(promise, pushStream));
            queued = flusher.append(entry);
        }
        // Iterate outside the synchronized block.
        if (queued)
            flusher.iterate();
    }


    @Override
    public void settings(SettingsFrame frame, Callback callback)
    {
        control(null, callback, frame);
    }

    @Override
    public void ping(PingFrame frame, Callback callback)
    {
        if (frame.isReply())
            callback.failed(new IllegalArgumentException());
        else
            control(null, callback, frame);
    }

    protected void reset(ResetFrame frame, Callback callback)
    {
        control(getStream(frame.getStreamId()), callback, frame);
    }

    /**
     * Invoked internally and by applications to send a GO_AWAY frame to the
     * other peer. We check the close state to act appropriately:
     *
     * * NOT_CLOSED: we move to LOCALLY_CLOSED and queue a GO_AWAY. When the
     *   GO_AWAY has been written, it will only cause the output to be shut
     *   down (not the connection closed), so that the application can still
     *   read frames arriving from the other peer.
     *   Ideally the other peer will notice the GO_AWAY and close the connection.
     *   When that happen, we close the connection from {@link #onShutdown()}.
     *   Otherwise, the idle timeout mechanism will close the connection, see
     *   {@link #onIdleTimeout()}.
     *
     * * In all other cases, we do nothing since other methods are already
     *   performing their actions.
     *
     * @param error the error code
     * @param reason the reason
     * @param callback the callback to invoke when the operation is complete
     * @see #onGoAway(GoAwayFrame)
     * @see #onShutdown()
     * @see #onIdleTimeout()
     */
    @Override
    public boolean close(int error, String reason, Callback callback)
    {
        while (true)
        {
            CloseState current = closed.get();
            switch (current)
            {
                case NOT_CLOSED:
                {
                    if (closed.compareAndSet(current, CloseState.LOCALLY_CLOSED))
                    {
                        byte[] payload = reason == null ? null : reason.getBytes(StandardCharsets.UTF_8);
                        GoAwayFrame frame = new GoAwayFrame(lastStreamId.get(), error, payload);
                        control(null, callback, frame);
                        return true;
                    }
                    break;
                }
                default:
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Ignoring close {}/{}, already closed", error, reason);
                    callback.succeeded();
                    return false;
                }
            }
        }
    }

    @Override
    public boolean isClosed()
    {
        return closed.get() != CloseState.NOT_CLOSED;
    }

    private void control(IStream stream, Callback callback, Frame frame)
    {
        frames(stream, callback, frame, Frame.EMPTY_ARRAY);
    }

    @Override
    public void frames(IStream stream, Callback callback, Frame frame, Frame... frames)
    {
        // We want to generate as late as possible to allow re-prioritization;
        // generation will happen while processing the entries.

        // The callback needs to be notified only when the last frame completes.

        int length = frames.length;
        if (length == 0)
        {
            frame(new ControlEntry(frame, stream, callback), true);
        }
        else
        {
            callback = new CountingCallback(callback, 1 + length);
            frame(new ControlEntry(frame, stream, callback), false);
            for (int i = 1; i <= length; ++i)
                frame(new ControlEntry(frames[i - 1], stream, callback), i == length);
        }
    }

    @Override
    public void data(IStream stream, Callback callback, DataFrame frame)
    {
        // We want to generate as late as possible to allow re-prioritization.
        frame(new DataEntry(frame, stream, callback), true);
    }

    private void frame(HTTP2Flusher.Entry entry, boolean flush)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Sending {}", entry.frame);
        // Ping frames are prepended to process them as soon as possible.
        boolean queued = entry.frame.getType() == FrameType.PING ? flusher.prepend(entry) : flusher.append(entry);
        if (queued && flush)
            flusher.iterate();
    }

    protected IStream createLocalStream(int streamId, Promise<Stream> promise)
    {
        while (true)
        {
            int localCount = localStreamCount.get();
            int maxCount = maxLocalStreams;
            if (maxCount >= 0 && localCount >= maxCount)
            {
                promise.failed(new IllegalStateException("Max local stream count " + maxCount + " exceeded"));
                return null;
            }
            if (localStreamCount.compareAndSet(localCount, localCount + 1))
                break;
        }

        IStream stream = newStream(streamId, true);
        if (streams.putIfAbsent(streamId, stream) == null)
        {
            stream.setIdleTimeout(getStreamIdleTimeout());
            flowControl.onStreamCreated(stream);
            if (LOG.isDebugEnabled())
                LOG.debug("Created local {}", stream);
            return stream;
        }
        else
        {
            promise.failed(new IllegalStateException("Duplicate stream " + streamId));
            return null;
        }
    }

    protected IStream createRemoteStream(int streamId)
    {
        // SPEC: exceeding max concurrent streams is treated as stream error.
        while (true)
        {
            int remoteCount = remoteStreamCount.get();
            int maxCount = getMaxRemoteStreams();
            if (maxCount >= 0 && remoteCount >= maxCount)
            {
                reset(new ResetFrame(streamId, ErrorCode.REFUSED_STREAM_ERROR.code), Callback.NOOP);
                return null;
            }
            if (remoteStreamCount.compareAndSet(remoteCount, remoteCount + 1))
                break;
        }

        IStream stream = newStream(streamId, false);

        // SPEC: duplicate stream is treated as connection error.
        if (streams.putIfAbsent(streamId, stream) == null)
        {
            updateLastStreamId(streamId);
            stream.setIdleTimeout(getStreamIdleTimeout());
            flowControl.onStreamCreated(stream);
            if (LOG.isDebugEnabled())
                LOG.debug("Created remote {}", stream);
            return stream;
        }
        else
        {
            close(ErrorCode.PROTOCOL_ERROR.code, "duplicate_stream", Callback.NOOP);
            return null;
        }
    }

    protected IStream newStream(int streamId, boolean local)
    {
        return new HTTP2Stream(scheduler, this, streamId, local);
    }

    @Override
    public void removeStream(IStream stream)
    {
        IStream removed = streams.remove(stream.getId());
        if (removed != null)
        {
            assert removed == stream;

            boolean local = stream.isLocal();
            if (local)
                localStreamCount.decrementAndGet();
            else
                remoteStreamCount.decrementAndGet();

            flowControl.onStreamDestroyed(stream);

            if (LOG.isDebugEnabled())
                LOG.debug("Removed {} {}", local ? "local" : "remote", stream);
        }
    }

    @Override
    public Collection<Stream> getStreams()
    {
        List<Stream> result = new ArrayList<>();
        result.addAll(streams.values());
        return result;
    }

    @Override
    public IStream getStream(int streamId)
    {
        return streams.get(streamId);
    }

    public int getSendWindow()
    {
        return sendWindow.get();
    }

    public int getRecvWindow()
    {
        return recvWindow.get();
    }

    @Override
    public int updateSendWindow(int delta)
    {
        return sendWindow.getAndAdd(delta);
    }

    @Override
    public int updateRecvWindow(int delta)
    {
        return recvWindow.getAndAdd(delta);
    }

    @Override
    public void onWindowUpdate(IStream stream, WindowUpdateFrame frame)
    {
        // WindowUpdateFrames arrive concurrently with writes.
        // Increasing (or reducing) the window size concurrently
        // with writes requires coordination with the flusher, that
        // decides how many frames to write depending on the available
        // window sizes. If the window sizes vary concurrently, the
        // flusher may take non-optimal or wrong decisions.
        // Here, we "queue" window updates to the flusher, so it will
        // be the only component responsible for window updates, for
        // both increments and reductions.
        flusher.window(stream, frame);
    }

    @Override
    public boolean isPushEnabled()
    {
        return pushEnabled;
    }

    /**
     * A typical close by a remote peer involves a GO_AWAY frame followed by TCP FIN.
     * This method is invoked when the TCP FIN is received, or when an exception is
     * thrown while reading, and we check the close state to act appropriately:
     *
     * * NOT_CLOSED: means that the remote peer did not send a GO_AWAY (abrupt close)
     *   or there was an exception while reading, and therefore we terminate.
     *
     * * LOCALLY_CLOSED: we have sent the GO_AWAY to the remote peer, which received
     *   it and closed the connection; we queue a disconnect to close the connection
     *   on the local side.
     *   The GO_AWAY just shutdown the output, so we need this step to make sure the
     *   connection is closed. See {@link #close(int, String, Callback)}.
     *
     * * REMOTELY_CLOSED: we received the GO_AWAY, and the TCP FIN afterwards, so we
     *   do nothing since the handling of the GO_AWAY will take care of closing the
     *   connection. See {@link #onGoAway(GoAwayFrame)}.
     *
     * @see #onGoAway(GoAwayFrame)
     * @see #close(int, String, Callback)
     * @see #onIdleTimeout()
     */
    @Override
    public void onShutdown()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Shutting down {}", this);

        switch (closed.get())
        {
            case NOT_CLOSED:
            {
                // The other peer did not send a GO_AWAY, no need to be gentle.
                if (LOG.isDebugEnabled())
                    LOG.debug("Abrupt close for {}", this);
                abort(new ClosedChannelException());
                break;
            }
            case LOCALLY_CLOSED:
            {
                // We have closed locally, and only shutdown
                // the output; now queue a disconnect.
                control(null, Callback.NOOP, new DisconnectFrame());
                break;
            }
            case REMOTELY_CLOSED:
            {
                // Nothing to do, the GO_AWAY frame we
                // received will close the connection.
                break;
            }
            default:
            {
                break;
            }
        }
    }

    /**
     * This method is invoked when the idle timeout triggers. We check the close state
     * to act appropriately:
     *
     * * NOT_CLOSED: it's a real idle timeout, we just initiate a close, see
     *   {@link #close(int, String, Callback)}.
     *
     * * LOCALLY_CLOSED: we have sent a GO_AWAY and only shutdown the output, but the
     *   other peer did not close the connection so we never received the TCP FIN, and
     *   therefore we terminate.
     *
     * * REMOTELY_CLOSED: the other peer sent us a GO_AWAY, we should have queued a
     *   disconnect, but for some reason it was not processed (for example, queue was
     *   stuck because of TCP congestion), therefore we terminate.
     *   See {@link #onGoAway(GoAwayFrame)}.
     *
     * @see #onGoAway(GoAwayFrame)
     * @see #close(int, String, Callback)
     * @see #onShutdown()
     */
    @Override
    public void onIdleTimeout()
    {
        switch (closed.get())
        {
            case NOT_CLOSED:
            {
                // Real idle timeout, just close.
                close(ErrorCode.NO_ERROR.code, "idle_timeout", Callback.NOOP);
                break;
            }
            case LOCALLY_CLOSED:
            case REMOTELY_CLOSED:
            {
                abort(new TimeoutException());
                break;
            }
            default:
            {
                break;
            }
        }
    }

    @Override
    public void onFrame(Frame frame)
    {
        onConnectionFailure(ErrorCode.PROTOCOL_ERROR.code, "upgrade");
    }

    public void disconnect()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Disconnecting {}", this);
        endPoint.close();
    }

    private void terminate()
    {
        while (true)
        {
            CloseState current = closed.get();
            switch (current)
            {
                case NOT_CLOSED:
                case LOCALLY_CLOSED:
                case REMOTELY_CLOSED:
                {
                    if (closed.compareAndSet(current, CloseState.CLOSED))
                    {
                        flusher.close();
                        for (IStream stream : streams.values())
                            stream.close();
                        streams.clear();
                        disconnect();
                        return;
                    }
                    break;
                }
                default:
                {
                    return;
                }
            }
        }
    }

    protected void abort(Throwable failure)
    {
        terminate();
        notifyFailure(this, failure);
    }

    public boolean isDisconnected()
    {
        return !endPoint.isOpen();
    }

    private void updateLastStreamId(int streamId)
    {
        Atomics.updateMax(lastStreamId, streamId);
    }

    protected Stream.Listener notifyNewStream(Stream stream, HeadersFrame frame)
    {
        try
        {
            return listener.onNewStream(stream, frame);
        }
        catch (Throwable x)
        {
            LOG.info("Failure while notifying listener " + listener, x);
            return null;
        }
    }

    protected void notifySettings(Session session, SettingsFrame frame)
    {
        try
        {
            listener.onSettings(session, frame);
        }
        catch (Throwable x)
        {
            LOG.info("Failure while notifying listener " + listener, x);
        }
    }

    protected void notifyPing(Session session, PingFrame frame)
    {
        try
        {
            listener.onPing(session, frame);
        }
        catch (Throwable x)
        {
            LOG.info("Failure while notifying listener " + listener, x);
        }
    }

    protected void notifyReset(Session session, ResetFrame frame)
    {
        try
        {
            listener.onReset(session, frame);
        }
        catch (Throwable x)
        {
            LOG.info("Failure while notifying listener " + listener, x);
        }
    }

    protected void notifyClose(Session session, GoAwayFrame frame)
    {
        try
        {
            listener.onClose(session, frame);
        }
        catch (Throwable x)
        {
            LOG.info("Failure while notifying listener " + listener, x);
        }
    }

    protected void notifyFailure(Session session, Throwable failure)
    {
        try
        {
            listener.onFailure(session, failure);
        }
        catch (Throwable x)
        {
            LOG.info("Failure while notifying listener " + listener, x);
        }
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{queueSize=%d,sendWindow=%s,recvWindow=%s,streams=%d,%s}", getClass().getSimpleName(),
                hashCode(), flusher.getQueueSize(), sendWindow, recvWindow, streams.size(), closed);
    }

    private class ControlEntry extends HTTP2Flusher.Entry
    {
        private ControlEntry(Frame frame, IStream stream, Callback callback)
        {
            super(frame, stream, callback);
        }

        public Throwable generate(ByteBufferPool.Lease lease)
        {
            try
            {
                generator.control(lease, frame);
                if (LOG.isDebugEnabled())
                    LOG.debug("Generated {}", frame);
                prepare();
                return null;
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Failure generating frame " + frame, x);
                return x;
            }
        }

        /**
         * <p>Performs actions just before writing the frame to the network.</p>
         * <p>Some frame, when sent over the network, causes the receiver
         * to react and send back frames that may be processed by the original
         * sender *before* {@link #succeeded()} is called.
         * <p>If the action to perform updates some state, this update may
         * not be seen by the received frames and cause errors.</p>
         * <p>For example, suppose the action updates the stream window to a
         * larger value; the sender sends the frame; the receiver is now entitled
         * to send back larger data; when the data is received by the original
         * sender, the action may have not been performed yet, causing the larger
         * data to be rejected, when it should have been accepted.</p>
         */
        private void prepare()
        {
            switch (frame.getType())
            {
                case SETTINGS:
                {
                    SettingsFrame settingsFrame = (SettingsFrame)frame;
                    Integer initialWindow = settingsFrame.getSettings().get(SettingsFrame.INITIAL_WINDOW_SIZE);
                    if (initialWindow != null)
                        flowControl.updateInitialStreamWindow(HTTP2Session.this, initialWindow, true);
                    break;
                }
                default:
                {
                    break;
                }
            }
        }

        @Override
        public void succeeded()
        {
            switch (frame.getType())
            {
                case HEADERS:
                {
                    HeadersFrame headersFrame = (HeadersFrame)frame;
                    if (stream.updateClose(headersFrame.isEndStream(), true))
                        removeStream(stream);
                    break;
                }
                case RST_STREAM:
                {
                    if (stream != null)
                    {
                        stream.close();
                        removeStream(stream);
                    }
                    break;
                }
                case PUSH_PROMISE:
                {
                    // Pushed streams are implicitly remotely closed.
                    // They are closed when sending an end-stream DATA frame.
                    stream.updateClose(true, false);
                    break;
                }
                case GO_AWAY:
                {
                    // We just sent a GO_AWAY, only shutdown the
                    // output without closing yet, to allow reads.
                    getEndPoint().shutdownOutput();
                    break;
                }
                case WINDOW_UPDATE:
                {
                    flowControl.windowUpdate(HTTP2Session.this, stream, (WindowUpdateFrame)frame);
                    break;
                }
                case DISCONNECT:
                {
                    terminate();
                    break;
                }
                default:
                {
                    break;
                }
            }
            callback.succeeded();
        }
    }

    private class DataEntry extends HTTP2Flusher.Entry
    {
        private int length;

        private DataEntry(DataFrame frame, IStream stream, Callback callback)
        {
            super(frame, stream, callback);
        }

        @Override
        public int dataRemaining()
        {
            // We don't do any padding, so the flow control length is
            // always the data remaining. This simplifies the handling
            // of data frames that cannot be completely written due to
            // the flow control window exhausting, since in that case
            // we would have to count the padding only once.
            return ((DataFrame)frame).remaining();
        }

        public Throwable generate(ByteBufferPool.Lease lease)
        {
            try
            {
                int flowControlLength = dataRemaining();

                int sessionSendWindow = getSendWindow();
                if (sessionSendWindow < 0)
                    throw new IllegalStateException();

                int streamSendWindow = stream.updateSendWindow(0);
                if (streamSendWindow < 0)
                    throw new IllegalStateException();

                int window = Math.min(streamSendWindow, sessionSendWindow);

                int length = this.length = Math.min(flowControlLength, window);
                if (LOG.isDebugEnabled())
                    LOG.debug("Generated {}, length/window={}/{}", frame, length, window);

                generator.data(lease, (DataFrame)frame, length);
                flowControl.onDataSending(stream, length);
                return null;
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Failure generating frame " + frame, x);
                return x;
            }
        }

        @Override
        public void succeeded()
        {
            flowControl.onDataSent(stream, length);
            // Do we have more to send ?
            DataFrame dataFrame = (DataFrame)frame;
            if (dataFrame.remaining() > 0)
            {
                // We have written part of the frame, but there is more to write.
                // The API will not allow to send two data frames for the same
                // stream so we append the unfinished frame at the end to allow
                // better interleaving with other streams.
                flusher.append(this);
            }
            else
            {
                // Only now we can update the close state
                // and eventually remove the stream.
                if (stream.updateClose(dataFrame.isEndStream(), true))
                    removeStream(stream);
                callback.succeeded();
            }
        }
    }

    private static class PromiseCallback<C> implements Callback
    {
        private final Promise<C> promise;
        private final C value;

        private PromiseCallback(Promise<C> promise, C value)
        {
            this.promise = promise;
            this.value = value;
        }

        @Override
        public void succeeded()
        {
            promise.succeeded(value);
        }

        @Override
        public void failed(Throwable x)
        {
            promise.failed(x);
        }
    }
}
