//
//  ========================================================================
//  Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.FailureFrame;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.FrameType;
import org.eclipse.jetty.http2.frames.GoAwayFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PingFrame;
import org.eclipse.jetty.http2.frames.PrefaceFrame;
import org.eclipse.jetty.http2.frames.PriorityFrame;
import org.eclipse.jetty.http2.frames.PushPromiseFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.http2.frames.StreamFrame;
import org.eclipse.jetty.http2.frames.WindowUpdateFrame;
import org.eclipse.jetty.http2.generator.Generator;
import org.eclipse.jetty.http2.hpack.HpackException;
import org.eclipse.jetty.http2.parser.Parser;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.WriteFlusher;
import org.eclipse.jetty.util.AtomicBiInteger;
import org.eclipse.jetty.util.Atomics;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.CountingCallback;
import org.eclipse.jetty.util.MathUtils;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Invocable;
import org.eclipse.jetty.util.thread.Locker;
import org.eclipse.jetty.util.thread.Scheduler;

@ManagedObject
public abstract class HTTP2Session extends ContainerLifeCycle implements ISession, Parser.Listener
{
    private static final Logger LOG = Log.getLogger(HTTP2Session.class);

    private final ConcurrentMap<Integer, IStream> streams = new ConcurrentHashMap<>();
    private final AtomicLong streamsOpened = new AtomicLong();
    private final AtomicLong streamsClosed = new AtomicLong();
    private final StreamsState streamsState = new StreamsState();
    private final AtomicInteger localStreamIds = new AtomicInteger();
    private final AtomicInteger lastRemoteStreamId = new AtomicInteger();
    private final AtomicInteger localStreamCount = new AtomicInteger();
    private final AtomicBiInteger remoteStreamCount = new AtomicBiInteger();
    private final AtomicInteger sendWindow = new AtomicInteger();
    private final AtomicInteger recvWindow = new AtomicInteger();
    private final AtomicLong bytesWritten = new AtomicLong();
    private final Scheduler scheduler;
    private final EndPoint endPoint;
    private final Generator generator;
    private final Session.Listener listener;
    private final FlowControlStrategy flowControl;
    private final HTTP2Flusher flusher;
    private int maxLocalStreams;
    private int maxRemoteStreams;
    private long streamIdleTimeout;
    private int initialSessionRecvWindow;
    private int writeThreshold;
    private boolean pushEnabled;

    public HTTP2Session(Scheduler scheduler, EndPoint endPoint, Generator generator, Session.Listener listener, FlowControlStrategy flowControl, int initialStreamId)
    {
        this.scheduler = scheduler;
        this.endPoint = endPoint;
        this.generator = generator;
        this.listener = listener;
        this.flowControl = flowControl;
        this.flusher = new HTTP2Flusher(this);
        this.maxLocalStreams = -1;
        this.maxRemoteStreams = -1;
        this.localStreamIds.set(initialStreamId);
        this.streamIdleTimeout = endPoint.getIdleTimeout();
        this.sendWindow.set(FlowControlStrategy.DEFAULT_WINDOW_SIZE);
        this.recvWindow.set(FlowControlStrategy.DEFAULT_WINDOW_SIZE);
        this.writeThreshold = 32 * 1024;
        this.pushEnabled = true; // SPEC: by default, push is enabled.
        addBean(flowControl);
        addBean(flusher);
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        streamsState.halt("stop");
    }

    @ManagedAttribute(value = "The flow control strategy", readonly = true)
    public FlowControlStrategy getFlowControlStrategy()
    {
        return flowControl;
    }

    @ManagedAttribute(value = "The total number of streams opened", readonly = true)
    public long getStreamsOpened()
    {
        return streamsOpened.get();
    }

    @ManagedAttribute(value = "The total number of streams closed", readonly = true)
    public long getStreamsClosed()
    {
        return streamsClosed.get();
    }

    @ManagedAttribute("The maximum number of concurrent local streams")
    public int getMaxLocalStreams()
    {
        return maxLocalStreams;
    }

    public void setMaxLocalStreams(int maxLocalStreams)
    {
        this.maxLocalStreams = maxLocalStreams;
    }

    @ManagedAttribute("The maximum number of concurrent remote streams")
    public int getMaxRemoteStreams()
    {
        return maxRemoteStreams;
    }

    public void setMaxRemoteStreams(int maxRemoteStreams)
    {
        this.maxRemoteStreams = maxRemoteStreams;
    }

    @ManagedAttribute("The stream's idle timeout")
    public long getStreamIdleTimeout()
    {
        return streamIdleTimeout;
    }

    public void setStreamIdleTimeout(long streamIdleTimeout)
    {
        this.streamIdleTimeout = streamIdleTimeout;
    }

    @ManagedAttribute("The initial size of session's flow control receive window")
    public int getInitialSessionRecvWindow()
    {
        return initialSessionRecvWindow;
    }

    public void setInitialSessionRecvWindow(int initialSessionRecvWindow)
    {
        this.initialSessionRecvWindow = initialSessionRecvWindow;
    }

    @ManagedAttribute("The number of bytes that trigger a TCP write")
    public int getWriteThreshold()
    {
        return writeThreshold;
    }

    public void setWriteThreshold(int writeThreshold)
    {
        this.writeThreshold = writeThreshold;
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
    public long getBytesWritten()
    {
        return bytesWritten.get();
    }

    @Override
    public void onData(DataFrame frame)
    {
        onData(frame, Callback.NOOP);
    }

    @Override
    public void onData(DataFrame frame, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Received {} on {}", frame, this);

        int streamId = frame.getStreamId();
        IStream stream = getStream(streamId);

        // SPEC: the session window must be updated even if the stream is null.
        // The flow control length includes the padding bytes.
        int flowControlLength = frame.remaining() + frame.padding();
        flowControl.onDataReceived(this, stream, flowControlLength);

        if (stream != null)
        {
            if (getRecvWindow() < 0)
            {
                onSessionFailure(ErrorCode.FLOW_CONTROL_ERROR.code, "session_window_exceeded", callback);
            }
            else
            {
                if (stream.updateRecvWindow(0) < 0)
                {
                    // It's a bad client, it does not deserve to be
                    // treated gently by just resetting the stream.
                    onSessionFailure(ErrorCode.FLOW_CONTROL_ERROR.code, "stream_window_exceeded", callback);
                }
                else
                {
                    stream.process(frame, new DataCallback(callback, stream, flowControlLength));
                }
            }
        }
        else
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Stream #{} not found on {}", streamId, this);
            // We must enlarge the session flow control window,
            // otherwise other requests will be stalled.
            flowControl.onDataConsumed(this, null, flowControlLength);
            if (isStreamClosed(streamId))
                reset(null, new ResetFrame(streamId, ErrorCode.STREAM_CLOSED_ERROR.code), callback);
            else
                onSessionFailure(ErrorCode.PROTOCOL_ERROR.code, "unexpected_data_frame", callback);
        }
    }

    private boolean isStreamClosed(int streamId)
    {
        return isLocalStream(streamId) ? isLocalStreamClosed(streamId) : isRemoteStreamClosed(streamId);
    }

    private boolean isLocalStream(int streamId)
    {
        return (streamId & 1) == (localStreamIds.get() & 1);
    }

    protected boolean isLocalStreamClosed(int streamId)
    {
        return streamId <= localStreamIds.get();
    }

    protected boolean isRemoteStreamClosed(int streamId)
    {
        return streamId <= getLastRemoteStreamId();
    }

    @Override
    public abstract void onHeaders(HeadersFrame frame);

    @Override
    public void onPriority(PriorityFrame frame)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Received {} on {}", frame, this);
    }

    @Override
    public void onReset(ResetFrame frame)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Received {} on {}", frame, this);

        int streamId = frame.getStreamId();
        IStream stream = getStream(streamId);
        if (stream != null)
        {
            stream.process(frame, new OnResetCallback());
        }
        else
        {
            onResetForUnknownStream(frame);
        }
    }

    protected void onResetForUnknownStream(ResetFrame frame)
    {
        if (isStreamClosed(frame.getStreamId()))
            notifyReset(this, frame);
        else
            onConnectionFailure(ErrorCode.PROTOCOL_ERROR.code, "unexpected_rst_stream_frame");
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
            LOG.debug("Received {} on {}", frame, this);

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
                        LOG.debug("Updating HPACK header table size to {} for {}", value, this);
                    generator.setHeaderTableSize(value);
                    break;
                }
                case SettingsFrame.ENABLE_PUSH:
                {
                    boolean enabled = value == 1;
                    if (LOG.isDebugEnabled())
                        LOG.debug("{} push for {}", enabled ? "Enabling" : "Disabling", this);
                    pushEnabled = enabled;
                    break;
                }
                case SettingsFrame.MAX_CONCURRENT_STREAMS:
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Updating max local concurrent streams to {} for {}", value, this);
                    maxLocalStreams = value;
                    break;
                }
                case SettingsFrame.INITIAL_WINDOW_SIZE:
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Updating initial stream window size to {} for {}", value, this);
                    flowControl.updateInitialStreamWindow(this, value, false);
                    break;
                }
                case SettingsFrame.MAX_FRAME_SIZE:
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Updating max frame size to {} for {}", value, this);
                    generator.setMaxFrameSize(value);
                    break;
                }
                case SettingsFrame.MAX_HEADER_LIST_SIZE:
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Updating max header list size to {} for {}", value, this);
                    generator.setMaxHeaderListSize(value);
                    break;
                }
                default:
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Unknown setting {}:{} for {}", key, value, this);
                    break;
                }
            }
        }
        notifySettings(this, frame);

        if (reply)
        {
            SettingsFrame replyFrame = new SettingsFrame(Collections.emptyMap(), true);
            settings(replyFrame, Callback.NOOP);
        }
    }

    @Override
    public void onPing(PingFrame frame)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Received {} on {}", frame, this);

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
     * <p>This method is called when receiving a GO_AWAY from the other peer.</p>
     *
     * @param frame the GO_AWAY frame that has been received.
     * @see #close(int, String, Callback)
     * @see #onShutdown()
     * @see #onIdleTimeout()
     */
    @Override
    public void onGoAway(GoAwayFrame frame)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Received {} on {}", frame, this);
        streamsState.onGoAway(frame);
    }

    @Override
    public void onWindowUpdate(WindowUpdateFrame frame)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Received {} on {}", frame, this);

        int streamId = frame.getStreamId();
        int windowDelta = frame.getWindowDelta();
        if (streamId > 0)
        {
            IStream stream = getStream(streamId);
            if (stream != null)
            {
                int streamSendWindow = stream.updateSendWindow(0);
                if (MathUtils.sumOverflows(streamSendWindow, windowDelta))
                {
                    reset(stream, new ResetFrame(streamId, ErrorCode.FLOW_CONTROL_ERROR.code), Callback.NOOP);
                }
                else
                {
                    stream.process(frame, Callback.NOOP);
                    onWindowUpdate(stream, frame);
                }
            }
            else
            {
                if (!isStreamClosed(streamId))
                    onConnectionFailure(ErrorCode.PROTOCOL_ERROR.code, "unexpected_window_update_frame");
            }
        }
        else
        {
            int sessionSendWindow = updateSendWindow(0);
            if (MathUtils.sumOverflows(sessionSendWindow, windowDelta))
                onConnectionFailure(ErrorCode.FLOW_CONTROL_ERROR.code, "invalid_flow_control_window");
            else
                onWindowUpdate(null, frame);
        }
    }

    @Override
    public void onStreamFailure(int streamId, int error, String reason)
    {
        Callback callback = Callback.from(() -> reset(getStream(streamId), new ResetFrame(streamId, error), Callback.NOOP));
        Throwable failure = toFailure(error, reason);
        if (LOG.isDebugEnabled())
            LOG.debug("Stream #{} failure {}", streamId, this, failure);
        IStream stream = getStream(streamId);
        if (stream != null)
            failStream(stream, error, reason, failure, callback);
        else
            callback.succeeded();
    }

    @Override
    public void onConnectionFailure(int error, String reason)
    {
        onSessionFailure(error, reason, Callback.NOOP);
    }

    private void onSessionFailure(int error, String reason, Callback callback)
    {
        streamsState.onSessionFailure(error, reason, callback);
    }

    void onWriteFailure(Throwable failure)
    {
        streamsState.onWriteFailure(failure);
    }

    protected void abort(String reason, Throwable failure, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Session abort {} for {}", reason, this, failure);
        onFailure(ErrorCode.NO_ERROR.code, reason, failure, callback);
    }

    private void onFailure(int error, String reason, Throwable failure, Callback callback)
    {
        Collection<Stream> streams = getStreams();
        int count = streams.size();
        Callback countCallback = new CountingCallback(callback, count + 1);
        for (Stream stream : streams)
        {
            if (stream.isClosed())
                countCallback.succeeded();
            else
                failStream(stream, error, reason, failure, countCallback);
        }
        notifyFailure(this, failure, countCallback);
    }

    private void failStreams(Predicate<IStream> matcher, String reason, boolean reset)
    {
        int error = ErrorCode.CANCEL_STREAM_ERROR.code;
        Throwable failure = toFailure(error, reason);
        for (Stream stream : getStreams())
        {
            if (stream.isClosed())
                continue;
            if (!matcher.test((IStream)stream))
                continue;
            if (LOG.isDebugEnabled())
                LOG.debug("Failing stream {} of {}", stream, this);
            failStream(stream, error, reason, failure, Callback.NOOP);
            if (reset)
                stream.reset(new ResetFrame(stream.getId(), error), Callback.NOOP);
        }
    }

    private void failStream(Stream stream, int error, String reason, Throwable failure, Callback callback)
    {
        ((IStream)stream).process(new FailureFrame(error, reason, failure), callback);
    }

    private Throwable toFailure(int error, String reason)
    {
        return new IOException(String.format("%s/%s", ErrorCode.toString(error, null), reason));
    }

    @Override
    public void newStream(HeadersFrame frame, Promise<Stream> promise, Stream.Listener listener)
    {
        newStream(new IStream.FrameList(frame), promise, listener);
    }

    @Override
    public void newStream(IStream.FrameList frames, Promise<Stream> promise, Stream.Listener listener)
    {
        streamsState.newLocalStream(frames, promise, listener);
    }

    @Override
    public int priority(PriorityFrame frame, Callback callback)
    {
        return streamsState.priority(frame, callback);
    }

    @Override
    public void push(IStream stream, Promise<Stream> promise, PushPromiseFrame frame, Stream.Listener listener)
    {
        streamsState.push(frame, new Promise.Wrapper<Stream>(promise)
        {
            @Override
            public void succeeded(Stream pushedStream)
            {
                // Pushed streams are implicitly remotely closed.
                // They are closed when sending an end-stream DATA frame.
                ((IStream)pushedStream).updateClose(true, CloseState.Event.RECEIVED);
                super.succeeded(pushedStream);
            }
        }, listener);
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

    void reset(IStream stream, ResetFrame frame, Callback callback)
    {
        control(stream, Callback.from(() ->
        {
            if (stream != null)
            {
                stream.close();
                removeStream(stream);
            }
        }, callback), frame);
    }

    /**
     * <p>Invoked internally and by applications to send a GO_AWAY frame to the other peer.</p>
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
        if (LOG.isDebugEnabled())
            LOG.debug("Closing {}/{} {}", ErrorCode.toString(error, null), reason, this);
        return goAway(newGoAwayFrame(error, reason), callback);
    }

    public boolean goAway(GoAwayFrame frame, Callback callback)
    {
        return streamsState.goAway(frame, callback);
    }

    private GoAwayFrame newGoAwayFrame(int error, String reason)
    {
        return newGoAwayFrame(getLastRemoteStreamId(), error, reason);
    }

    private GoAwayFrame newGoAwayFrame(int lastRemoteStreamId, int error, String reason)
    {
        byte[] payload = null;
        if (reason != null)
        {
            // Trim the reason to avoid attack vectors.
            reason = reason.substring(0, Math.min(reason.length(), 32));
            payload = reason.getBytes(StandardCharsets.UTF_8);
        }
        return new GoAwayFrame(lastRemoteStreamId, error, payload);
    }

    @Override
    public boolean isClosed()
    {
        return getCloseState() != CloseState.NOT_CLOSED;
    }

    public CloseState getCloseState()
    {
        return streamsState.getCloseState();
    }

    private void control(IStream stream, Callback callback, Frame frame)
    {
        frames(stream, Collections.singletonList(frame), callback);
    }

    @Override
    public void frames(IStream stream, List<? extends Frame> frames, Callback callback)
    {
        // We want to generate as late as possible to allow re-prioritization;
        // generation will happen while processing the entries.

        // The callback needs to be notified only when the last frame completes.

        int count = frames.size();
        if (count > 1)
            callback = new CountingCallback(callback, count);
        for (int i = 1; i <= count; ++i)
        {
            Frame frame = frames.get(i - 1);
            HTTP2Flusher.Entry entry = newEntry(frame, stream, callback);
            frame(entry, i == count);
        }
    }

    private HTTP2Flusher.Entry newEntry(Frame frame, IStream stream, Callback callback)
    {
        return frame.getType() == FrameType.DATA
            ? new DataEntry((DataFrame)frame, stream, callback)
            : new ControlEntry(frame, stream, callback);
    }

    @Override
    public void data(IStream stream, Callback callback, DataFrame frame)
    {
        // We want to generate as late as possible to allow re-prioritization.
        frame(newEntry(frame, stream, callback), true);
    }

    private void frame(HTTP2Flusher.Entry entry, boolean flush)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} {} on {}", flush ? "Sending" : "Queueing", entry, this);
        // Ping frames are prepended to process them as soon as possible.
        boolean queued = entry.hasHighPriority() ? flusher.prepend(entry) : flusher.append(entry);
        if (queued && flush)
        {
            if (entry.stream != null)
                entry.stream.notIdle();
            flusher.iterate();
        }
    }

    protected IStream createLocalStream(int streamId, Promise<Stream> promise)
    {
        while (true)
        {
            int localCount = localStreamCount.get();
            int maxCount = getMaxLocalStreams();
            if (maxCount >= 0 && localCount >= maxCount)
            {
                IllegalStateException failure = new IllegalStateException("Max local stream count " + maxCount + " exceeded: " + localCount);
                if (LOG.isDebugEnabled())
                    LOG.debug("Could not create local stream #{} for {}", streamId, this, failure);
                promise.failed(failure);
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
                LOG.debug("Created local {} for {}", stream, this);
            return stream;
        }
        else
        {
            localStreamCount.decrementAndGet();
            promise.failed(new IllegalStateException("Duplicate stream " + streamId));
            return null;
        }
    }

    protected IStream createRemoteStream(int streamId)
    {
        // This stream has been seen the server.
        // Even if the stream cannot be created because this peer is closing,
        // updating the lastRemoteStreamId ensures that in-flight HEADERS and
        // DATA frames can be read (and discarded) without causing an error.
        updateLastRemoteStreamId(streamId);

        if (!streamsState.newRemoteStream(streamId))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Could not create remote stream #{} for {}", streamId, this);
            return null;
        }

        // SPEC: exceeding max concurrent streams is treated as stream error.
        while (true)
        {
            long encoded = remoteStreamCount.get();
            int remoteCount = AtomicBiInteger.getHi(encoded);
            int remoteClosing = AtomicBiInteger.getLo(encoded);
            int maxCount = getMaxRemoteStreams();
            if (maxCount >= 0 && remoteCount - remoteClosing >= maxCount)
            {
                IllegalStateException failure = new IllegalStateException("Max remote stream count " + maxCount + " exceeded: " + remoteCount + "+" + remoteClosing);
                if (LOG.isDebugEnabled())
                    LOG.debug("Could not create remote stream #{} for {}", streamId, this, failure);
                reset(null, new ResetFrame(streamId, ErrorCode.REFUSED_STREAM_ERROR.code), Callback.from(() -> onStreamDestroyed(streamId)));
                return null;
            }
            if (remoteStreamCount.compareAndSet(encoded, remoteCount + 1, remoteClosing))
                break;
        }

        IStream stream = newStream(streamId, false);
        if (streams.putIfAbsent(streamId, stream) == null)
        {
            stream.setIdleTimeout(getStreamIdleTimeout());
            flowControl.onStreamCreated(stream);
            if (LOG.isDebugEnabled())
                LOG.debug("Created remote {} for {}", stream, this);
            return stream;
        }
        else
        {
            remoteStreamCount.addAndGetHi(-1);
            onStreamDestroyed(streamId);
            // SPEC: duplicate stream is treated as connection error.
            onConnectionFailure(ErrorCode.PROTOCOL_ERROR.code, "duplicate_stream");
            return null;
        }
    }

    void updateStreamCount(boolean local, int deltaStreams, int deltaClosing)
    {
        if (local)
            localStreamCount.addAndGet(deltaStreams);
        else
            remoteStreamCount.add(deltaStreams, deltaClosing);
    }

    protected IStream newStream(int streamId, boolean local)
    {
        return new HTTP2Stream(scheduler, this, streamId, local);
    }

    @Override
    public boolean removeStream(IStream stream)
    {
        int streamId = stream.getId();
        IStream removed = streams.remove(streamId);
        if (removed == null)
            return false;
        if (LOG.isDebugEnabled())
            LOG.debug("Removed {} {} from {}", stream.isLocal() ? "local" : "remote", stream, this);
        onStreamClosed(stream);
        flowControl.onStreamDestroyed(stream);
        onStreamDestroyed(streamId);
        return true;
    }

    @Override
    public Collection<Stream> getStreams()
    {
        return new ArrayList<>(streams.values());
    }

    @ManagedAttribute("The number of active streams")
    public int getStreamCount()
    {
        return streamsState.streamCount.intValue();
    }

    @Override
    public IStream getStream(int streamId)
    {
        return streams.get(streamId);
    }

    @ManagedAttribute(value = "The flow control send window", readonly = true)
    public int getSendWindow()
    {
        return sendWindow.get();
    }

    @ManagedAttribute(value = "The flow control receive window", readonly = true)
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
    @ManagedAttribute(value = "Whether HTTP/2 push is enabled", readonly = true)
    public boolean isPushEnabled()
    {
        return pushEnabled;
    }

    /**
     * <p>This method is called when the TCP FIN is received from the remote peer.</p>
     *
     * @see #onGoAway(GoAwayFrame)
     * @see #close(int, String, Callback)
     * @see #onIdleTimeout()
     */
    @Override
    public void onShutdown()
    {
        streamsState.onShutdown();
    }

    /**
     * <p>This method is invoked when the idle timeout expires.</p>
     *
     * @return true if the session should be closed, false otherwise
     * @see #onGoAway(GoAwayFrame)
     * @see #close(int, String, Callback)
     * @see #onShutdown()
     */
    @Override
    public boolean onIdleTimeout()
    {
        return streamsState.onIdleTimeout();
    }

    private void notIdle()
    {
        streamsState.idleTime = System.nanoTime();
    }

    @Override
    public void onFrame(Frame frame)
    {
        onConnectionFailure(ErrorCode.PROTOCOL_ERROR.code, "upgrade");
    }

    private void onStreamCreated(int streamId)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Creating stream #{} for {}", streamId, this);
        streamsState.onStreamCreated();
    }

    protected final void onStreamOpened(IStream stream)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Opened stream {} for {}", stream, this);
        streamsOpened.incrementAndGet();
    }

    private void onStreamClosed(IStream stream)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Closed stream {} for {}", stream, this);
        streamsClosed.incrementAndGet();
    }

    private void onStreamDestroyed(int streamId)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Destroyed stream #{} for {}", streamId, this);
        streamsState.onStreamDestroyed();
    }

    @Override
    public void onFlushed(long bytes) throws IOException
    {
        flusher.onFlushed(bytes);
    }

    private void terminate(Throwable cause)
    {
        flusher.terminate(cause);
        disconnect();
    }

    public void disconnect()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Disconnecting {}", this);
        endPoint.close();
    }

    public boolean isDisconnected()
    {
        return !endPoint.isOpen();
    }

    protected int getLastRemoteStreamId()
    {
        return lastRemoteStreamId.get();
    }

    private void updateLastRemoteStreamId(int streamId)
    {
        Atomics.updateMax(lastRemoteStreamId, streamId);
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

    protected void notifyGoAway(Session session, GoAwayFrame frame)
    {
        try
        {
            listener.onGoAway(session, frame);
        }
        catch (Throwable x)
        {
            LOG.info("Failure while notifying listener " + listener, x);
        }
    }

    protected void notifyClose(Session session, GoAwayFrame frame, Callback callback)
    {
        try
        {
            listener.onClose(session, frame, callback);
        }
        catch (Throwable x)
        {
            LOG.info("Failure while notifying listener " + listener, x);
        }
    }

    protected boolean notifyIdleTimeout(Session session)
    {
        try
        {
            return listener.onIdleTimeout(session);
        }
        catch (Throwable x)
        {
            LOG.info("Failure while notifying listener " + listener, x);
            return true;
        }
    }

    protected void notifyFailure(Session session, Throwable failure, Callback callback)
    {
        try
        {
            listener.onFailure(session, failure, callback);
        }
        catch (Throwable x)
        {
            LOG.info("Failure while notifying listener " + listener, x);
        }
    }

    protected void notifyHeaders(IStream stream, HeadersFrame frame)
    {
        Stream.Listener listener = stream.getListener();
        if (listener == null)
            return;
        try
        {
            listener.onHeaders(stream, frame);
        }
        catch (Throwable x)
        {
            LOG.info("Failure while notifying listener " + listener, x);
        }
    }

    protected static boolean isClientStream(int streamId)
    {
        // Client-initiated stream ids are odd.
        return (streamId & 1) == 1;
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        dumpObjects(out, indent, new DumpableCollection("streams", streams.values()));
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{local:%s,remote:%s,sendWindow=%s,recvWindow=%s,%s}",
            getClass().getSimpleName(),
            hashCode(),
            getEndPoint().getLocalAddress(),
            getEndPoint().getRemoteAddress(),
            sendWindow,
            recvWindow,
            streamsState
        );
    }

    private class ControlEntry extends HTTP2Flusher.Entry
    {
        private int frameBytes;

        private ControlEntry(Frame frame, IStream stream, Callback callback)
        {
            super(frame, stream, callback);
        }

        @Override
        public int getFrameBytesGenerated()
        {
            return frameBytes;
        }

        @Override
        protected boolean generate(ByteBufferPool.Lease lease) throws HpackException
        {
            frameBytes = generator.control(lease, frame);
            beforeSend();
            return true;
        }

        @Override
        public long onFlushed(long bytes)
        {
            long flushed = Math.min(frameBytes, bytes);
            if (LOG.isDebugEnabled())
                LOG.debug("Flushed {}/{} frame bytes for {}", flushed, bytes, this);
            frameBytes -= flushed;
            return bytes - flushed;
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
        private void beforeSend()
        {
            switch (frame.getType())
            {
                case HEADERS:
                {
                    HeadersFrame headersFrame = (HeadersFrame)frame;
                    stream.updateClose(headersFrame.isEndStream(), CloseState.Event.BEFORE_SEND);
                    break;
                }
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
        boolean hasHighPriority()
        {
            return frame.getType() == FrameType.PING;
        }

        @Override
        public void succeeded()
        {
            commit();

            bytesWritten.addAndGet(frameBytes);
            frameBytes = 0;

            switch (frame.getType())
            {
                case HEADERS:
                {
                    HeadersFrame headersFrame = (HeadersFrame)frame;
                    if (headersFrame.getMetaData().isRequest())
                        onStreamOpened(stream);
                    if (stream.updateClose(headersFrame.isEndStream(), CloseState.Event.AFTER_SEND))
                        removeStream(stream);
                    break;
                }
                case WINDOW_UPDATE:
                {
                    flowControl.windowUpdate(HTTP2Session.this, stream, (WindowUpdateFrame)frame);
                    break;
                }
                default:
                {
                    break;
                }
            }

            super.succeeded();
        }
    }

    private class DataEntry extends HTTP2Flusher.Entry
    {
        private int frameBytes;
        private int frameRemaining;
        private int dataBytes;
        private int dataRemaining;

        private DataEntry(DataFrame frame, IStream stream, Callback callback)
        {
            super(frame, stream, callback);
            // We don't do any padding, so the flow control length is
            // always the data remaining. This simplifies the handling
            // of data frames that cannot be completely written due to
            // the flow control window exhausting, since in that case
            // we would have to count the padding only once.
            dataRemaining = frame.remaining();
        }

        @Override
        public int getFrameBytesGenerated()
        {
            return frameBytes;
        }

        @Override
        public int getDataBytesRemaining()
        {
            return dataRemaining;
        }

        @Override
        protected boolean generate(ByteBufferPool.Lease lease)
        {
            int dataRemaining = getDataBytesRemaining();

            int sessionSendWindow = getSendWindow();
            int streamSendWindow = stream.updateSendWindow(0);
            int window = Math.min(streamSendWindow, sessionSendWindow);
            if (window <= 0 && dataRemaining > 0)
                return false;

            int length = Math.min(dataRemaining, window);

            // Only one DATA frame is generated.
            DataFrame dataFrame = (DataFrame)frame;
            int frameBytes = generator.data(lease, dataFrame, length);
            this.frameBytes += frameBytes;
            this.frameRemaining += frameBytes;

            int dataBytes = frameBytes - Frame.HEADER_LENGTH;
            this.dataBytes += dataBytes;
            this.dataRemaining -= dataBytes;
            if (LOG.isDebugEnabled())
                LOG.debug("Generated {}, length/window/data={}/{}/{}", dataFrame, dataBytes, window, dataRemaining);

            flowControl.onDataSending(stream, dataBytes);

            stream.updateClose(dataFrame.isEndStream(), CloseState.Event.BEFORE_SEND);

            return true;
        }

        @Override
        public long onFlushed(long bytes) throws IOException
        {
            long flushed = Math.min(frameRemaining, bytes);
            if (LOG.isDebugEnabled())
                LOG.debug("Flushed {}/{} frame bytes for {}", flushed, bytes, this);
            frameRemaining -= flushed;
            // We should only forward data (not frame) bytes,
            // but we trade precision for simplicity.
            Object channel = stream.getAttachment();
            if (channel instanceof WriteFlusher.Listener)
                ((WriteFlusher.Listener)channel).onFlushed(flushed);
            return bytes - flushed;
        }

        @Override
        public void succeeded()
        {
            bytesWritten.addAndGet(frameBytes);
            frameBytes = 0;
            frameRemaining = 0;

            flowControl.onDataSent(stream, dataBytes);
            dataBytes = 0;

            // Do we have more to send ?
            DataFrame dataFrame = (DataFrame)frame;
            if (getDataBytesRemaining() == 0)
            {
                // Only now we can update the close state
                // and eventually remove the stream.
                if (stream.updateClose(dataFrame.isEndStream(), CloseState.Event.AFTER_SEND))
                    removeStream(stream);
                super.succeeded();
            }
        }
    }

    private class DataCallback extends Callback.Nested
    {
        private final IStream stream;
        private final int flowControlLength;

        public DataCallback(Callback callback, IStream stream, int flowControlLength)
        {
            super(callback);
            this.stream = stream;
            this.flowControlLength = flowControlLength;
        }

        @Override
        public void succeeded()
        {
            complete();
            super.succeeded();
        }

        @Override
        public void failed(Throwable x)
        {
            // Consume also in case of failures, to free the
            // session flow control window for other streams.
            complete();
            super.failed(x);
        }

        private void complete()
        {
            notIdle();
            stream.notIdle();
            flowControl.onDataConsumed(HTTP2Session.this, stream, flowControlLength);
        }
    }

    private class OnResetCallback implements Callback
    {
        @Override
        public void succeeded()
        {
            complete();
        }

        @Override
        public void failed(Throwable x)
        {
            complete();
        }

        @Override
        public InvocationType getInvocationType()
        {
            return InvocationType.NON_BLOCKING;
        }

        private void complete()
        {
            flusher.iterate();
        }
    }

    /**
     * <p>The HTTP/2 specification requires that stream ids are monotonically increasing,
     * see https://tools.ietf.org/html/rfc7540#section-5.1.1.</p>
     * <p>This implementation uses a queue to atomically reserve a stream id and claim
     * a slot in the queue; the slot is then assigned the entries to write.</p>
     * <p>Concurrent threads push slots in the queue but only one thread flushes
     * the slots, up to the slot that has a non-null entries to write, therefore
     * guaranteeing that frames are sent strictly in their stream id order.</p>
     * <p>This class also coordinates the creation of streams with the close of
     * the session, see https://tools.ietf.org/html/rfc7540#section-6.8.</p>
     */
    private class StreamsState
    {
        private final Locker lock = new Locker();
        private final Queue<Slot> slots = new ArrayDeque<>();
        // Must be incremented with the lock held.
        private final AtomicLong streamCount = new AtomicLong();
        private long idleTime = System.nanoTime();
        private CloseState closed = CloseState.NOT_CLOSED;
        private Runnable zeroStreamsAction;
        private GoAwayFrame goAwayRecv;
        private GoAwayFrame goAwaySent;
        private Throwable failure;
        private Thread flushing;

        private CloseState getCloseState()
        {
            try (Locker.Lock l = lock.lock())
            {
                return closed;
            }
        }

        private boolean goAway(GoAwayFrame frame, Callback callback)
        {
            boolean sendGoAway = false;
            boolean tryRunZeroStreamsAction = false;
            try (Locker.Lock l = lock.lock())
            {
                switch (closed)
                {
                    case NOT_CLOSED:
                    {
                        goAwaySent = frame;
                        closed = CloseState.LOCALLY_CLOSED;
                        sendGoAway = true;
                        if (frame.isGraceful())
                        {
                            // Try to send the non-graceful GOAWAY
                            // when the last stream is destroyed.
                            zeroStreamsAction = () ->
                            {
                                GoAwayFrame goAwayFrame = newGoAwayFrame(ErrorCode.NO_ERROR.code, "close");
                                goAway(goAwayFrame, Callback.NOOP);
                            };
                            tryRunZeroStreamsAction = streamCount.get() == 0;
                        }
                        break;
                    }
                    case LOCALLY_CLOSED:
                    {
                        if (frame.isGraceful())
                        {
                            // Trying to send a non-first, but graceful, GOAWAY, ignore this one.
                            if (LOG.isDebugEnabled())
                                LOG.debug("Already sent, ignored GOAWAY {} for {}", frame, HTTP2Session.this);
                        }
                        else
                        {
                            // SPEC: see section 6.8.
                            if (goAwaySent.isGraceful() ||
                                frame.getLastStreamId() < goAwaySent.getLastStreamId() ||
                                frame.getError() != ErrorCode.NO_ERROR.code)
                            {
                                goAwaySent = frame;
                                sendGoAway = true;
                            }
                            else
                            {
                                // Trying to send another non-graceful GOAWAY, ignore this one.
                                if (LOG.isDebugEnabled())
                                    LOG.debug("Already sent, ignored GOAWAY {} for {}", frame, HTTP2Session.this);
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
                            // Try to send the non-graceful GOAWAY
                            // when the last stream is destroyed.
                            zeroStreamsAction = () ->
                            {
                                GoAwayFrame goAwayFrame = newGoAwayFrame(ErrorCode.NO_ERROR.code, "close");
                                goAway(goAwayFrame, Callback.NOOP);
                            };
                            tryRunZeroStreamsAction = streamCount.get() == 0;
                        }
                        else
                        {
                            if (goAwayRecv.isGraceful())
                            {
                                if (LOG.isDebugEnabled())
                                    LOG.debug("Waiting non-graceful GOAWAY for {}", HTTP2Session.this);
                            }
                            else
                            {
                                closed = CloseState.CLOSING;
                                zeroStreamsAction = () -> terminate(frame);
                                tryRunZeroStreamsAction = streamCount.get() == 0;
                            }
                        }
                        break;
                    }
                    default:
                    {
                        // Already closing or closed, ignore it.
                        if (LOG.isDebugEnabled())
                            LOG.debug("Already closed, ignored {} for {}", frame, HTTP2Session.this);
                        break;
                    }
                }
            }

            if (sendGoAway)
            {
                if (tryRunZeroStreamsAction)
                    sendGoAway(frame, Callback.from(callback, this::tryRunZeroStreamsAction));
                else
                    sendGoAway(frame, callback);
                return true;
            }
            else
            {
                callback.succeeded();
                return false;
            }
        }

        private void halt(String reason)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Halting ({}) for {}", reason, HTTP2Session.this);
            GoAwayFrame goAwayFrame = null;
            GoAwayFrame goAwayFrameEvent;
            try (Locker.Lock l = lock.lock())
            {
                switch (closed)
                {
                    case NOT_CLOSED:
                    case REMOTELY_CLOSED:
                    case LOCALLY_CLOSED:
                    case CLOSING:
                    {
                        if (goAwaySent == null || goAwaySent.isGraceful())
                            goAwaySent = goAwayFrame = newGoAwayFrame(ErrorCode.NO_ERROR.code, reason);
                        goAwayFrameEvent = goAwayRecv != null ? goAwayRecv : goAwaySent;
                        closed = CloseState.CLOSED;
                        zeroStreamsAction = null;
                        if (failure != null)
                            failure = toFailure(ErrorCode.NO_ERROR.code, reason);
                        break;
                    }
                    default:
                    {
                        return;
                    }
                }
            }
            failStreams(stream -> true, reason, true);
            if (goAwayFrame != null)
                sendGoAwayAndTerminate(goAwayFrame, goAwayFrameEvent);
            else
                terminate(goAwayFrameEvent);
        }

        private void onGoAway(GoAwayFrame frame)
        {
            boolean failStreams = false;
            boolean tryRunZeroStreamsAction = false;
            try (Locker.Lock l = lock.lock())
            {
                switch (closed)
                {
                    case NOT_CLOSED:
                    {
                        goAwayRecv = frame;
                        if (frame.isGraceful())
                        {
                            closed = CloseState.REMOTELY_CLOSED;
                            if (LOG.isDebugEnabled())
                                LOG.debug("Waiting non-graceful GOAWAY for {}", HTTP2Session.this);
                        }
                        else
                        {
                            goAwaySent = newGoAwayFrame(ErrorCode.NO_ERROR.code, "close");
                            closed = CloseState.CLOSING;
                            GoAwayFrame goAwayFrame = goAwaySent;
                            zeroStreamsAction = () -> sendGoAwayAndTerminate(goAwayFrame, frame);
                            tryRunZeroStreamsAction = streamCount.get() == 0;
                            failStreams = true;
                        }
                        break;
                    }
                    case LOCALLY_CLOSED:
                    {
                        goAwayRecv = frame;
                        if (frame.isGraceful())
                        {
                            // Wait for the non-graceful GOAWAY from the other peer.
                            if (LOG.isDebugEnabled())
                                LOG.debug("Waiting non-graceful GOAWAY for {}", HTTP2Session.this);
                        }
                        else
                        {
                            closed = CloseState.CLOSING;
                            if (goAwaySent.isGraceful())
                            {
                                goAwaySent = newGoAwayFrame(ErrorCode.NO_ERROR.code, "close");
                                GoAwayFrame goAwayFrame = goAwaySent;
                                zeroStreamsAction = () -> sendGoAwayAndTerminate(goAwayFrame, frame);
                                tryRunZeroStreamsAction = streamCount.get() == 0;
                            }
                            else
                            {
                                zeroStreamsAction = () -> terminate(frame);
                                tryRunZeroStreamsAction = streamCount.get() == 0;
                                failStreams = true;
                            }
                        }
                        break;
                    }
                    case REMOTELY_CLOSED:
                    {
                        if (frame.isGraceful())
                        {
                            // Received a non-first, but graceful, GOAWAY, ignore it.
                            if (LOG.isDebugEnabled())
                                LOG.debug("Already received, ignoring GOAWAY for {}", HTTP2Session.this);
                        }
                        else
                        {
                            goAwayRecv = frame;
                            closed = CloseState.CLOSING;
                            if (goAwaySent == null || goAwaySent.isGraceful())
                            {
                                goAwaySent = newGoAwayFrame(ErrorCode.NO_ERROR.code, "close");
                                GoAwayFrame goAwayFrame = goAwaySent;
                                zeroStreamsAction = () -> sendGoAwayAndTerminate(goAwayFrame, frame);
                            }
                            else
                            {
                                zeroStreamsAction = () -> terminate(frame);
                            }
                            tryRunZeroStreamsAction = streamCount.get() == 0;
                            failStreams = true;
                        }
                        break;
                    }
                    default:
                    {
                        // Already closing or closed, ignore it.
                        if (LOG.isDebugEnabled())
                            LOG.debug("Already closed, ignored {} for {}", frame, HTTP2Session.this);
                        break;
                    }
                }
            }

            notifyGoAway(HTTP2Session.this, frame);

            if (failStreams)
            {
                // Must compare the lastStreamId only with local streams.
                // For example, a client that sent request with streamId=137 may send a GOAWAY(4),
                // where streamId=4 is the last stream pushed by the server to the client.
                // The server must not compare its local streamId=4 with remote streamId=137.
                Predicate<IStream> failIf = stream -> stream.isLocal() && stream.getId() > frame.getLastStreamId();
                failStreams(failIf, "closing", false);
            }

            if (tryRunZeroStreamsAction)
                tryRunZeroStreamsAction();
        }

        private void onShutdown()
        {
            String reason = "input_shutdown";
            Throwable cause = null;
            boolean failStreams = false;
            try (Locker.Lock l = lock.lock())
            {
                switch (closed)
                {
                    case NOT_CLOSED:
                    case LOCALLY_CLOSED:
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("Unexpected ISHUT for {}", HTTP2Session.this);
                        closed = CloseState.CLOSING;
                        failure = cause = new ClosedChannelException();
                        break;
                    }
                    case REMOTELY_CLOSED:
                    {
                        closed = CloseState.CLOSING;
                        GoAwayFrame goAwayFrame = newGoAwayFrame(0, ErrorCode.NO_ERROR.code, reason);
                        zeroStreamsAction = () -> terminate(goAwayFrame);
                        failure = new ClosedChannelException();
                        failStreams = true;
                        break;
                    }
                    case CLOSING:
                    {
                        if (failure == null)
                            failure = new ClosedChannelException();
                        failStreams = true;
                        break;
                    }
                    default:
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("Already closed, ignoring ISHUT for {}", HTTP2Session.this);
                        return;
                    }
                }
            }

            if (failStreams)
            {
                // Since nothing else will arrive from the other peer, reset
                // the streams for which the other peer did not send all frames.
                Predicate<IStream> failIf = stream -> !stream.isRemotelyClosed();
                failStreams(failIf, reason, false);
                tryRunZeroStreamsAction();
            }
            else
            {
                GoAwayFrame goAwayFrame = newGoAwayFrame(0, ErrorCode.NO_ERROR.code, reason);
                abort(reason, cause, Callback.from(() -> terminate(goAwayFrame)));
            }
        }

        private boolean onIdleTimeout()
        {
            String reason = "idle_timeout";
            boolean notify = false;
            boolean sendGoAway = false;
            GoAwayFrame goAwayFrame = null;
            Throwable cause = null;
            try (Locker.Lock l = lock.lock())
            {
                switch (closed)
                {
                    case NOT_CLOSED:
                    {
                        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - idleTime);
                        if (elapsed < endPoint.getIdleTimeout())
                            return false;
                        notify = true;
                        break;
                    }
                    // Timed out while waiting for closing events, fail all the streams.
                    case LOCALLY_CLOSED:
                    {
                        if (goAwaySent.isGraceful())
                        {
                            goAwaySent = newGoAwayFrame(ErrorCode.NO_ERROR.code, reason);
                            sendGoAway = true;
                        }
                        goAwayFrame = goAwaySent;
                        closed = CloseState.CLOSING;
                        zeroStreamsAction = null;
                        failure = cause = new TimeoutException("Session idle timeout expired");
                        break;
                    }
                    case REMOTELY_CLOSED:
                    {
                        goAwaySent = newGoAwayFrame(ErrorCode.NO_ERROR.code, reason);
                        sendGoAway = true;
                        goAwayFrame = goAwaySent;
                        closed = CloseState.CLOSING;
                        zeroStreamsAction = null;
                        failure = cause = new TimeoutException("Session idle timeout expired");
                        break;
                    }
                    default:
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("Already closed, ignored idle timeout for {}", HTTP2Session.this);
                        return false;
                    }
                }
            }

            if (notify)
            {
                boolean confirmed = notifyIdleTimeout(HTTP2Session.this);
                if (LOG.isDebugEnabled())
                    LOG.debug("Idle timeout {} for {}", confirmed ? "confirmed" : "ignored", HTTP2Session.this);
                if (confirmed)
                    halt(reason);
                return false;
            }

            failStreams(stream -> true, reason, true);
            if (sendGoAway)
                sendGoAway(goAwayFrame, Callback.NOOP);
            notifyFailure(HTTP2Session.this, cause, Callback.NOOP);
            terminate(goAwayFrame);
            return false;
        }

        private void onSessionFailure(int error, String reason, Callback callback)
        {
            GoAwayFrame goAwayFrame;
            Throwable cause;
            try (Locker.Lock l = lock.lock())
            {
                switch (closed)
                {
                    case NOT_CLOSED:
                    case LOCALLY_CLOSED:
                    case REMOTELY_CLOSED:
                    {
                        // Send another GOAWAY with the error code.
                        goAwaySent = goAwayFrame = newGoAwayFrame(error, reason);
                        closed = CloseState.CLOSING;
                        zeroStreamsAction = null;
                        failure = cause = toFailure(error, reason);
                        break;
                    }
                    default:
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("Already closed, ignored session failure {}", HTTP2Session.this, failure);
                        callback.succeeded();
                        return;
                    }
                }
            }

            if (LOG.isDebugEnabled())
                LOG.debug("Session failure {}", HTTP2Session.this, cause);

            failStreams(stream -> true, reason, true);
            sendGoAway(goAwayFrame, Callback.NOOP);
            notifyFailure(HTTP2Session.this, cause, Callback.NOOP);
            terminate(goAwayFrame);
        }

        private void onWriteFailure(Throwable x)
        {
            String reason = "write_failure";
            try (Locker.Lock l = lock.lock())
            {
                switch (closed)
                {
                    case NOT_CLOSED:
                    case LOCALLY_CLOSED:
                    case REMOTELY_CLOSED:
                    {
                        closed = CloseState.CLOSING;
                        zeroStreamsAction = () ->
                        {
                            GoAwayFrame goAwayFrame = newGoAwayFrame(0, ErrorCode.NO_ERROR.code, reason);
                            terminate(goAwayFrame);
                        };
                        failure = x;
                        break;
                    }
                    default:
                    {
                        return;
                    }
                }
            }
            abort(reason, x, Callback.from(this::tryRunZeroStreamsAction));
        }

        private void sendGoAwayAndTerminate(GoAwayFrame frame, GoAwayFrame eventFrame)
        {
            sendGoAway(frame, Callback.from(Callback.NOOP, () -> terminate(eventFrame)));
        }

        private void sendGoAway(GoAwayFrame frame, Callback callback)
        {
            control(null, callback, frame);
        }

        private void onStreamCreated()
        {
            streamCount.incrementAndGet();
        }

        private void onStreamDestroyed()
        {
            long count = streamCount.decrementAndGet();
            // I've seen zero here, but it may increase again.
            // That's why tryRunZeroStreamsAction() must check
            // the count with the lock held.
            if (count == 0)
                tryRunZeroStreamsAction();
        }

        private void tryRunZeroStreamsAction()
        {
            // Threads from onStreamClosed() and other events
            // such as onGoAway() may be in a race to finish,
            // but only one moves to CLOSED and runs the action.
            Runnable action = null;
            try (Locker.Lock l = lock.lock())
            {
                long count = streamCount.get();
                if (count > 0)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Deferred closing action, {} pending streams on {}", count, HTTP2Session.this);
                    return;
                }

                switch (closed)
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
                        closed = CloseState.CLOSED;
                        action = zeroStreamsAction;
                        zeroStreamsAction = null;
                        break;
                    }
                    default:
                    {
                        break;
                    }
                }
            }
            if (action != null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Executing zero streams action on {}", HTTP2Session.this);
                action.run();
            }
        }

        private void terminate(GoAwayFrame frame)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Terminating {}", HTTP2Session.this);
            HTTP2Session.this.terminate(failure);
            notifyClose(HTTP2Session.this, frame, Callback.NOOP);
        }

        private int priority(PriorityFrame frame, Callback callback)
        {
            Slot slot = new Slot();
            int currentStreamId = frame.getStreamId();
            int streamId = reserveSlot(slot, currentStreamId, callback::failed);
            if (streamId > 0)
            {
                if (currentStreamId <= 0)
                    frame = frame.withStreamId(streamId);
                slot.entries = Collections.singletonList(newEntry(frame, null, Callback.from(callback::succeeded, x ->
                {
                    HTTP2Session.this.onStreamDestroyed(streamId);
                    callback.failed(x);
                })));
                flush();
            }
            return streamId;
        }

        private void newLocalStream(IStream.FrameList frameList, Promise<Stream> promise, Stream.Listener listener)
        {
            Slot slot = new Slot();
            int currentStreamId = frameList.getStreamId();
            int streamId = reserveSlot(slot, currentStreamId, promise::failed);
            if (streamId > 0)
            {
                List<StreamFrame> frames = frameList.getFrames();
                if (currentStreamId <= 0)
                {
                    frames = frames.stream()
                        .map(frame -> frame.withStreamId(streamId))
                        .collect(Collectors.toList());
                }
                if (createLocalStream(slot, frames, promise, listener, streamId))
                    return;
                freeSlot(slot, streamId);
            }
        }

        private boolean newRemoteStream(int streamId)
        {
            boolean created = false;
            try (Locker.Lock l = lock.lock())
            {
                switch (closed)
                {
                    case NOT_CLOSED:
                    {
                        created = true;
                        break;
                    }
                    case LOCALLY_CLOSED:
                    {
                        // SPEC: streams larger than GOAWAY's lastStreamId are dropped.
                        if (streamId <= goAwaySent.getLastStreamId())
                        {
                            // Allow creation of streams that may have been in-flight.
                            created = true;
                        }
                        break;
                    }
                    default:
                        break;
                }
            }
            if (created)
                HTTP2Session.this.onStreamCreated(streamId);
            return created;
        }

        private void push(PushPromiseFrame frame, Promise<Stream> promise, Stream.Listener listener)
        {
            Slot slot = new Slot();
            int streamId = reserveSlot(slot, 0, promise::failed);
            if (streamId > 0)
            {
                frame = frame.withStreamId(streamId);
                if (createLocalStream(slot, Collections.singletonList(frame), promise, listener, streamId))
                    return;
                freeSlot(slot, streamId);
            }
        }

        private boolean createLocalStream(Slot slot, List<StreamFrame> frames, Promise<Stream> promise, Stream.Listener listener, int streamId)
        {
            IStream stream = HTTP2Session.this.createLocalStream(streamId, promise);
            if (stream == null)
                return false;

            stream.setListener(listener);
            stream.process(new PrefaceFrame(), Callback.NOOP);

            Callback streamCallback = Callback.from(Invocable.InvocationType.NON_BLOCKING, () -> promise.succeeded(stream), x ->
            {
                HTTP2Session.this.onStreamDestroyed(streamId);
                promise.failed(x);
            });
            int count = frames.size();
            if (count == 1)
            {
                slot.entries = Collections.singletonList(newEntry(frames.get(0), stream, streamCallback));
            }
            else
            {
                Callback callback = new CountingCallback(streamCallback, count);
                slot.entries = frames.stream()
                    .map(frame -> newEntry(frame, stream, callback))
                    .collect(Collectors.toList());
            }
            flush();
            return true;
        }

        private int reserveSlot(Slot slot, int streamId, Consumer<Throwable> fail)
        {
            Throwable failure = null;
            boolean reserved = false;
            try (Locker.Lock l = lock.lock())
            {
                // SPEC: cannot create new streams after receiving a GOAWAY.
                if (closed == CloseState.NOT_CLOSED)
                {
                    if (streamId <= 0)
                    {
                        streamId = localStreamIds.getAndAdd(2);
                        reserved = true;
                    }
                    slots.offer(slot);
                }
                else
                {
                    failure = this.failure;
                    if (failure == null)
                        failure = new IllegalStateException("session closed");
                }
            }
            if (failure == null)
            {
                if (reserved)
                    HTTP2Session.this.onStreamCreated(streamId);
                return streamId;
            }
            else
            {
                fail.accept(failure);
                return 0;
            }
        }

        private void freeSlot(Slot slot, int streamId)
        {
            try (Locker.Lock l = lock.lock())
            {
                slots.remove(slot);
            }
            HTTP2Session.this.onStreamDestroyed(streamId);
            flush();
        }

        /**
         * <p>Iterates over the entries of the slot queue to flush them.</p>
         * <p>The flush proceeds until either one of the following two conditions is true:</p>
         * <ul>
         *     <li>the queue is empty</li>
         *     <li>a slot with a no entries is encountered</li>
         * </ul>
         * <p>When a slot with a no entries is encountered, then it means that a concurrent thread reserved
         * a slot but hasn't set its entries yet. Since slots must be flushed in order, the thread encountering
         * the slot with no entries must bail out and it is up to the concurrent thread to finish up flushing.</p>
         * <p>Note that only one thread can flush at any time; if two threads happen to call this method
         * concurrently, one will do the work while the other will bail out, so it is safe that all
         * threads call this method after they are done reserving a slot and setting the entries.</p>
         */
        private void flush()
        {
            Thread thread = Thread.currentThread();
            boolean queued = false;
            while (true)
            {
                List<HTTP2Flusher.Entry> entries;
                try (Locker.Lock l = lock.lock())
                {
                    if (flushing == null)
                        flushing = thread;
                    else if (flushing != thread)
                        return; // another thread is flushing

                    Slot slot = slots.peek();
                    entries = slot == null ? null : slot.entries;

                    if (entries == null)
                    {
                        flushing = null;
                        break; // No more slots or null entries, so we may iterate on the flusher
                    }

                    slots.poll();
                }
                queued |= flusher.append(entries);
            }
            if (queued)
                flusher.iterate();
        }

        @Override
        public String toString()
        {
            try (Locker.Lock l = lock.lock())
            {
                return String.format("state=[streams=%d,%s,goAwayRecv=%s,goAwaySent=%s,failure=%s]",
                    streamCount.get(),
                    closed,
                    goAwayRecv,
                    goAwaySent,
                    failure
                );
            }
        }

        private class Slot
        {
            private volatile List<HTTP2Flusher.Entry> entries;
        }
    }
}
