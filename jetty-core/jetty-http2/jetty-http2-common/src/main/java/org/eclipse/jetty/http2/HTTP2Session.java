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

package org.eclipse.jetty.http2;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EventListener;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.eclipse.jetty.http.MetaData;
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
import org.eclipse.jetty.http2.hpack.HpackEncoder;
import org.eclipse.jetty.http2.hpack.HpackException;
import org.eclipse.jetty.http2.internal.HTTP2Flusher;
import org.eclipse.jetty.http2.parser.Parser;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.CyclicTimeouts;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.AtomicBiInteger;
import org.eclipse.jetty.util.Atomics;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.CountingCallback;
import org.eclipse.jetty.util.MathUtils;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.Invocable;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ManagedObject
public abstract class HTTP2Session extends AbstractLifeCycle implements Session, Parser.Listener, Dumpable
{
    private static final Logger LOG = LoggerFactory.getLogger(HTTP2Session.class);
    // SPEC: stream numbers can go up to 2^31-1, but increment by 2.
    private static final int MAX_TOTAL_LOCAL_STREAMS = Integer.MAX_VALUE / 2;

    private final Map<Integer, HTTP2Stream> streams = new ConcurrentHashMap<>();
    private final Set<Integer> priorityStreams = ConcurrentHashMap.newKeySet();
    private final List<FrameListener> frameListeners = new CopyOnWriteArrayList<>();
    private final List<LifeCycleListener> lifeCycleListeners = new CopyOnWriteArrayList<>();
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
    private final AtomicInteger totalLocalStreams = new AtomicInteger();
    private final EndPoint endPoint;
    private final Parser parser;
    private final Generator generator;
    private final Session.Listener listener;
    private final FlowControlStrategy flowControl;
    private final HTTP2Flusher flusher;
    private final StreamTimeouts streamTimeouts;
    private int maxLocalStreams;
    private int maxRemoteStreams;
    private int maxTotalLocalStreams;
    private long streamIdleTimeout;
    private int initialSessionRecvWindow;
    private int writeThreshold;
    private int maxEncoderTableCapacity;
    private boolean pushEnabled;
    private boolean connectProtocolEnabled;

    public HTTP2Session(Scheduler scheduler, EndPoint endPoint, Parser parser, Generator generator, Session.Listener listener, FlowControlStrategy flowControl, int initialStreamId)
    {
        this.endPoint = endPoint;
        this.parser = parser;
        this.generator = generator;
        this.listener = listener;
        this.flowControl = flowControl;
        this.flusher = new HTTP2Flusher(this);
        this.streamTimeouts = new StreamTimeouts(scheduler);
        this.maxLocalStreams = -1;
        this.maxRemoteStreams = -1;
        this.maxTotalLocalStreams = MAX_TOTAL_LOCAL_STREAMS;
        this.localStreamIds.set(initialStreamId);
        this.sendWindow.set(FlowControlStrategy.DEFAULT_WINDOW_SIZE);
        this.recvWindow.set(FlowControlStrategy.DEFAULT_WINDOW_SIZE);
        this.writeThreshold = 32 * 1024;
        this.pushEnabled = true; // SPEC: by default, push is enabled.
    }

    @Override
    public boolean addEventListener(EventListener listener)
    {
        if (super.addEventListener(listener))
        {
            if (listener instanceof FrameListener frameListener)
                frameListeners.add(frameListener);
            if (listener instanceof LifeCycleListener lifeCycleListener)
                lifeCycleListeners.add(lifeCycleListener);
            return true;
        }
        return false;
    }

    @Override
    public boolean removeEventListener(EventListener listener)
    {
        if (super.removeEventListener(listener))
        {
            if (listener instanceof FrameListener frameListener)
                frameListeners.remove(frameListener);
            if (listener instanceof LifeCycleListener lifeCycleListener)
                lifeCycleListeners.remove(lifeCycleListener);
            return true;
        }
        return false;
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        streamsState.halt("stop");
    }

    public int getFrameQueueSize()
    {
        return flusher.getFrameQueueSize();
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

    @ManagedAttribute("The maximum number of local streams that can be opened")
    public int getMaxTotalLocalStreams()
    {
        return maxTotalLocalStreams;
    }

    public void setMaxTotalLocalStreams(int maxTotalLocalStreams)
    {
        if (maxTotalLocalStreams > MAX_TOTAL_LOCAL_STREAMS)
            throw new IllegalArgumentException("Invalid max total local streams " + maxTotalLocalStreams);
        if (maxTotalLocalStreams > 0)
            this.maxTotalLocalStreams = maxTotalLocalStreams;
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

    @ManagedAttribute("The HPACK encoder dynamic table maximum capacity")
    public int getMaxEncoderTableCapacity()
    {
        return maxEncoderTableCapacity;
    }

    public void setMaxEncoderTableCapacity(int maxEncoderTableCapacity)
    {
        this.maxEncoderTableCapacity = maxEncoderTableCapacity;
    }

    public EndPoint getEndPoint()
    {
        return endPoint;
    }

    public Parser getParser()
    {
        return parser;
    }

    public Generator getGenerator()
    {
        return generator;
    }

    public long getBytesWritten()
    {
        return bytesWritten.get();
    }

    @Override
    public void onData(DataFrame frame)
    {
        // This method should never be called, the one below should.
        throw new UnsupportedOperationException();
    }

    public void onData(Stream.Data data)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Received {} on {}", data, this);

        DataFrame frame = data.frame();
        notifyIncomingFrame(frame);

        int streamId = frame.getStreamId();
        HTTP2Stream stream = getStream(streamId);

        // SPEC: the session window must be updated even if the stream is null.
        // The flow control length includes the padding bytes.
        int flowControlLength = frame.flowControlLength();
        flowControl.onDataReceived(this, stream, flowControlLength);

        if (stream != null)
        {
            if (getRecvWindow() < 0)
            {
                onSessionFailure(ErrorCode.FLOW_CONTROL_ERROR.code, "session_window_exceeded", Callback.NOOP);
            }
            else
            {
                if (stream.updateRecvWindow(0) < 0)
                {
                    // It's a bad client, it does not deserve to be
                    // treated gently by just resetting the stream.
                    onSessionFailure(ErrorCode.FLOW_CONTROL_ERROR.code, "stream_window_exceeded", Callback.NOOP);
                }
                else
                {
                    stream.process(data);
                }
            }
        }
        else
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Stream #{} not found on {}", streamId, this);
            // We must enlarge the session flow control window,
            // otherwise other requests will be stalled.
            dataConsumed(null, flowControlLength);
            if (isStreamClosed(streamId))
                reset(null, new ResetFrame(streamId, ErrorCode.STREAM_CLOSED_ERROR.code), Callback.NOOP);
            else
                onSessionFailure(ErrorCode.PROTOCOL_ERROR.code, "unexpected_data_frame", Callback.NOOP);
        }
    }

    void dataConsumed(HTTP2Stream stream, int length)
    {
        notIdle();
        flowControl.onDataConsumed(this, stream, length);
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
    public void onHeaders(HeadersFrame frame)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Received {} on {}", frame, this);
        notifyIncomingFrame(frame);
    }

    @Override
    public void onPriority(PriorityFrame frame)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Received {} on {}", frame, this);
        notifyIncomingFrame(frame);
    }

    @Override
    public void onReset(ResetFrame frame)
    {
        int streamId = frame.getStreamId();
        HTTP2Stream stream = getStream(streamId);

        if (LOG.isDebugEnabled())
            LOG.debug("Received {} for {} on {}", frame, stream, this);

        notifyIncomingFrame(frame);

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

        notifyIncomingFrame(frame);

        if (frame.isReply())
            return;

        Map<Integer, Integer> settings = frame.getSettings();
        configure(settings, false);
        notifySettings(this, frame);

        if (reply)
        {
            SettingsFrame replyFrame = new SettingsFrame(Collections.emptyMap(), true);
            settings(replyFrame, Callback.NOOP);
        }
    }

    private void configure(Map<Integer, Integer> settings, boolean local)
    {
        for (Map.Entry<Integer, Integer> entry : settings.entrySet())
        {
            int key = entry.getKey();
            int value = entry.getValue();
            switch (key)
            {
                case SettingsFrame.HEADER_TABLE_SIZE ->
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Updating HPACK {} max table capacity to {} for {}", local ? "decoder" : "encoder", value, this);
                    if (local)
                    {
                        parser.getHpackDecoder().setMaxTableCapacity(value);
                    }
                    else
                    {
                        HpackEncoder hpackEncoder = generator.getHpackEncoder();
                        hpackEncoder.setMaxTableCapacity(value);
                        hpackEncoder.setTableCapacity(Math.min(value, getMaxEncoderTableCapacity()));
                    }
                }
                case SettingsFrame.ENABLE_PUSH ->
                {
                    boolean enabled = value == 1;
                    if (LOG.isDebugEnabled())
                        LOG.debug("{} push for {}", enabled ? "Enabling" : "Disabling", this);
                    pushEnabled = enabled;
                }
                case SettingsFrame.MAX_CONCURRENT_STREAMS ->
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Updating max {} concurrent streams to {} for {}", local ? "remote" : "local", value, this);
                    if (local)
                        maxRemoteStreams = value;
                    else
                        maxLocalStreams = value;
                }
                case SettingsFrame.INITIAL_WINDOW_SIZE ->
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Updating initial stream window size to {} for {}", value, this);
                    flowControl.updateInitialStreamWindow(this, value, local);
                }
                case SettingsFrame.MAX_FRAME_SIZE ->
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Updating {} max frame size to {} for {}", local ? "parser" : "generator", value, this);
                    if (local)
                        parser.setMaxFrameSize(value);
                    else
                        generator.setMaxFrameSize(value);
                }
                case SettingsFrame.MAX_HEADER_LIST_SIZE ->
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Updating {} max header list size to {} for {}", local ? "decoder" : "encoder", value, this);
                    if (local)
                    {
                        parser.getHpackDecoder().setMaxHeaderListSize(value);
                    }
                    else
                    {
                        HpackEncoder hpackEncoder = generator.getHpackEncoder();
                        hpackEncoder.setMaxHeaderListSize(Math.min(value, hpackEncoder.getMaxHeaderListSize()));
                    }
                }
                case SettingsFrame.ENABLE_CONNECT_PROTOCOL ->
                {
                    boolean enabled = value == 1;
                    if (LOG.isDebugEnabled())
                        LOG.debug("{} CONNECT protocol for {}", enabled ? "Enabling" : "Disabling", this);
                    connectProtocolEnabled = enabled;
                }
                default ->
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Unknown setting {}:{} for {}", key, value, this);
                }
            }
        }
    }

    @Override
    public void onPushPromise(PushPromiseFrame frame)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Received {} on {}", frame, this);
        notifyIncomingFrame(frame);
    }

    @Override
    public void onPing(PingFrame frame)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Received {} on {}", frame, this);

        notifyIncomingFrame(frame);

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

        notifyIncomingFrame(frame);

        streamsState.onGoAway(frame);
    }

    @Override
    public void onWindowUpdate(WindowUpdateFrame frame)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Received {} on {}", frame, this);

        notifyIncomingFrame(frame);

        int streamId = frame.getStreamId();
        int windowDelta = frame.getWindowDelta();
        if (streamId > 0)
        {
            HTTP2Stream stream = getStream(streamId);
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

    public void onWindowUpdate(HTTP2Stream stream, WindowUpdateFrame frame)
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
    public void onStreamFailure(int streamId, int error, String reason)
    {
        HTTP2Stream stream = getStream(streamId);
        Callback callback = Callback.from(() -> reset(stream, new ResetFrame(streamId, error), Callback.NOOP));
        Throwable failure = toFailure(error, reason);
        if (stream != null)
            onStreamFailure(stream, error, reason, failure, callback);
        else
            callback.succeeded();
    }

    protected void onStreamFailure(Stream stream, int error, String reason, Throwable failure, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Stream #{} failure {}", stream.getId(), this, failure);
        failStream(stream, error, reason, failure, callback);
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

    public void onWriteFailure(Throwable failure)
    {
        streamsState.onWriteFailure(failure);
    }

    /**
     * <p>Fails the streams that match the predicate, with error code
     * {@link ErrorCode#CANCEL_STREAM_ERROR} and {@link IOException}.</p>
     *
     * @param matcher the predicate to match
     * @param reason the failure reason
     * @param reset whether the stream will be reset
     * @param callback the callback that is completed when all the matching streams have been failed
     */
    private void failStreams(Predicate<Stream> matcher, String reason, boolean reset, Callback callback)
    {
        int error = ErrorCode.CANCEL_STREAM_ERROR.code;
        Throwable failure = toFailure(error, reason);
        failStreams(matcher, error, reason, failure, reset, callback);
    }

    /**
     * <p>Fails the streams that match the predicate, with the given error code, reason and failure.</p>
     *
     * @param matcher the predicate to match
     * @param error the HTTP/2 error code
     * @param reason the failure reason
     * @param failure the failure
     * @param reset whether the stream will be reset
     * @param callback the callback that is completed when all the matching streams have been failed
     */
    private void failStreams(Predicate<Stream> matcher, int error, String reason, Throwable failure, boolean reset, Callback callback)
    {
        Collection<Stream> streams = getStreams();
        int count = streams.size();

        if (LOG.isDebugEnabled())
            LOG.debug("Failing {} streams of {}", count, this);

        if (count == 0)
        {
            callback.succeeded();
            return;
        }

        CountingCallback counter = new CountingCallback(callback, count);
        for (Stream stream : streams)
        {
            if (stream.isClosed() || !matcher.test(stream))
            {
                counter.succeeded();
                continue;
            }
            failStream(stream, error, reason, failure, Callback.from(() ->
            {
                if (reset)
                    stream.reset(new ResetFrame(stream.getId(), error), Callback.from(counter::succeeded));
                else
                    counter.succeeded();
            }));
        }
    }

    private void failStream(Stream stream, int error, String reason, Throwable failure, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Failing stream {} of {}", stream, this);
        ((HTTP2Stream)stream).process(new FailureFrame(error, reason, failure), callback);
    }

    private Throwable toFailure(int error, String reason)
    {
        return new IOException(String.format("%s/%s", ErrorCode.toString(error, null), reason));
    }

    @Override
    public void newStream(HeadersFrame frame, Promise<Stream> promise, Stream.Listener listener)
    {
        newStream(new HTTP2Stream.FrameList(frame), promise, listener);
    }

    public void newStream(HTTP2Stream.FrameList frames, Promise<Stream> promise, Stream.Listener listener)
    {
        streamsState.newLocalStream(frames, promise, listener);
    }

    /**
     * <p>Creates a new stream allocating a stream id if the given HEADERS frame does not have one.</p>
     *
     * @param frame the HEADERS frame that triggered the stream creation
     * allocated stream id, or null if not interested in the modified headers frame
     * @param listener the listener that gets notified of stream events
     */
    public Stream newUpgradeStream(HeadersFrame frame, Stream.Listener listener, Consumer<Throwable> failFn)
    {
        return streamsState.newUpgradeStream(frame, listener, failFn);
    }

    protected HTTP2Stream newStream(int streamId, MetaData.Request request, boolean local)
    {
        return new HTTP2Stream(this, streamId, request, local);
    }

    @Override
    public int priority(PriorityFrame frame, Callback callback)
    {
        return streamsState.priority(frame, callback);
    }

    public void push(Stream stream, Promise<Stream> promise, PushPromiseFrame frame, Stream.Listener listener)
    {
        if (!isPushEnabled())
            throw new IllegalStateException("Push is disabled");
        streamsState.push(frame, new Promise.Wrapper<>(promise)
        {
            @Override
            public void succeeded(Stream pushed)
            {
                // Pushed streams are implicitly remotely closed.
                // They are closed when sending an end-stream DATA frame.
                HTTP2Stream http2Pushed = (HTTP2Stream)pushed;
                http2Pushed.process(Stream.Data.eof(pushed.getId()));
                http2Pushed.updateClose(true, CloseState.Event.RECEIVED);
                super.succeeded(pushed);
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

    void reset(HTTP2Stream stream, ResetFrame frame, Callback callback)
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

    @Override
    public CompletableFuture<Void> shutdown()
    {
        return streamsState.shutdown();
    }

    public boolean goAway(GoAwayFrame frame, Callback callback)
    {
        return streamsState.goAway(frame, callback);
    }

    private GoAwayFrame newGoAwayFrame(int error, String reason)
    {
        byte[] payload = null;
        if (reason != null)
        {
            // Trim the reason to avoid attack vectors.
            reason = reason.substring(0, Math.min(reason.length(), 32));
            payload = reason.getBytes(StandardCharsets.UTF_8);
        }
        return new GoAwayFrame(getLastRemoteStreamId(), error, payload);
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

    private void control(HTTP2Stream stream, Callback callback, Frame frame)
    {
        frames(stream, List.of(frame), callback);
    }

    public void frames(HTTP2Stream stream, List<? extends Frame> frames, Callback callback)
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
            Entry entry = newEntry(frame, stream, callback);
            frame(entry, i == count);
        }
    }

    private Entry newEntry(Frame frame, HTTP2Stream stream, Callback callback)
    {
        return frame.getType() == FrameType.DATA
            ? new DataEntry((DataFrame)frame, stream, callback)
            : new ControlEntry(frame, stream, callback);
    }

    public void data(HTTP2Stream stream, DataFrame frame, Callback callback)
    {
        // We want to generate as late as possible to allow re-prioritization.
        frame(newEntry(frame, stream, callback), true);
    }

    private void frame(Entry entry, boolean flush)
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

    protected HTTP2Stream createLocalStream(int streamId, MetaData.Request request, Consumer<Throwable> failFn)
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
                failFn.accept(failure);
                return null;
            }
            if (localStreamCount.compareAndSet(localCount, localCount + 1))
                break;
        }

        HTTP2Stream stream = newStream(streamId, request, true);
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
            failFn.accept(new IllegalStateException("Duplicate stream " + streamId));
            return null;
        }
    }

    protected HTTP2Stream createRemoteStream(int streamId, MetaData.Request request)
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

        HTTP2Stream stream = newStream(streamId, request, false);
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

    private boolean removeStream(int streamId)
    {
        HTTP2Stream removed = streams.get(streamId);
        if (removed != null)
            return removeStream(removed);
        priorityStreams.remove(streamId);
        onStreamClosed(streamId);
        onStreamDestroyed(streamId);
        return true;
    }

    public boolean removeStream(Stream stream)
    {
        int streamId = stream.getId();
        priorityStreams.remove(streamId);
        HTTP2Stream removed = streams.remove(streamId);
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
    public HTTP2Stream getStream(int streamId)
    {
        return streams.get(streamId);
    }

    @Override
    public InetSocketAddress getLocalAddress()
    {
        SocketAddress local = getLocalSocketAddress();
        if (local instanceof InetSocketAddress)
            return (InetSocketAddress)local;
        return null;
    }

    @Override
    public SocketAddress getLocalSocketAddress()
    {
        return endPoint.getLocalSocketAddress();
    }

    @Override
    public InetSocketAddress getRemoteAddress()
    {
        SocketAddress remote = getRemoteSocketAddress();
        if (remote instanceof InetSocketAddress)
            return (InetSocketAddress)remote;
        return null;
    }

    @Override
    public SocketAddress getRemoteSocketAddress()
    {
        return endPoint.getRemoteSocketAddress();
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

    public int updateSendWindow(int delta)
    {
        return sendWindow.getAndAdd(delta);
    }

    public int updateRecvWindow(int delta)
    {
        return recvWindow.getAndAdd(delta);
    }

    @Override
    @ManagedAttribute(value = "Whether HTTP/2 push is enabled", readonly = true)
    public boolean isPushEnabled()
    {
        return pushEnabled;
    }

    @ManagedAttribute(value = "Whether CONNECT requests supports a protocol", readonly = true)
    public boolean isConnectProtocolEnabled()
    {
        return connectProtocolEnabled;
    }

    public void setConnectProtocolEnabled(boolean connectProtocolEnabled)
    {
        this.connectProtocolEnabled = connectProtocolEnabled;
    }

    /**
     * <p>This method is called when the TCP FIN is received from the remote peer.</p>
     *
     * @see #onGoAway(GoAwayFrame)
     * @see #close(int, String, Callback)
     * @see #onIdleTimeout()
     */
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
    public boolean onIdleTimeout()
    {
        return streamsState.onIdleTimeout();
    }

    private void notIdle()
    {
        streamsState.idleNanoTime = NanoTime.now();
    }

    public void onFrame(Frame frame)
    {
        onConnectionFailure(ErrorCode.PROTOCOL_ERROR.code, "upgrade");
    }

    void scheduleTimeout(HTTP2Stream stream)
    {
        streamTimeouts.schedule(stream);
    }

    private void onStreamCreated(int streamId)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Creating stream #{} for {}", streamId, this);
        streamsState.onStreamCreated();
    }

    protected final void onStreamOpened(Stream stream)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Opened stream {} for {}", stream, this);
        streamsOpened.incrementAndGet();
    }

    private void onStreamClosed(Stream stream)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Closed stream {} for {}", stream, this);
        onStreamClosed(stream.getId());
    }

    private void onStreamClosed(int streamId)
    {
        streamsClosed.incrementAndGet();
    }

    private void onStreamDestroyed(int streamId)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Destroyed stream #{} for {}", streamId, this);
        streamsState.onStreamDestroyed();
    }

    private void terminate(Throwable cause)
    {
        flusher.terminate(cause);
        streamTimeouts.destroy();
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

    protected void updateLastRemoteStreamId(int streamId)
    {
        Atomics.updateMax(lastRemoteStreamId, streamId);
    }

    public void notifyLifeCycleOpen()
    {
        notifyLifeCycle(LifeCycleListener::onOpen);
    }

    private void notifyLifeCycleClose()
    {
        notifyLifeCycle(LifeCycleListener::onClose);
    }

    private void notifyLifeCycle(BiConsumer<LifeCycleListener, Session> method)
    {
        for (LifeCycleListener listener : lifeCycleListeners)
        {
            try
            {
                method.accept(listener, this);
            }
            catch (Throwable x)
            {
                LOG.info("Failure while notifying listener {}", listener, x);
            }
        }
    }

    private void notifyIncomingFrame(Frame frame)
    {
        notifyFrame(listener -> listener.onIncomingFrame(this, frame));
    }

    public void notifyOutgoingFrames(Collection<Entry> entries)
    {
        for (Entry entry : entries)
        {
            Frame frame = entry.frame();
            if (!frame.getType().isSynthetic())
                notifyFrame(listener -> listener.onOutgoingFrame(this, frame));
        }
    }

    private void notifyFrame(Consumer<FrameListener> method)
    {
        for (FrameListener listener : frameListeners)
        {
            try
            {
                method.accept(listener);
            }
            catch (Throwable x)
            {
                LOG.info("Failure while notifying listener {}", listener, x);
            }
        }
    }

    protected Stream.Listener notifyNewStream(Stream stream, HeadersFrame frame)
    {
        try
        {
            return listener.onNewStream(stream, frame);
        }
        catch (Throwable x)
        {
            LOG.info("Failure while notifying listener {}", listener, x);
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
            LOG.info("Failure while notifying listener {}", listener, x);
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
            LOG.info("Failure while notifying listener {}", listener, x);
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
            LOG.info("Failure while notifying listener {}", listener, x);
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
            LOG.info("Failure while notifying listener {}", listener, x);
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
            LOG.info("Failure while notifying listener {}", listener, x);
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
            LOG.info("Failure while notifying listener {}", listener, x);
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
        Dumpable.dumpObjects(out, indent, this, flowControl, flusher, new DumpableCollection("streams", streams.values()));
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{local:%s,remote:%s,sendWindow=%s,recvWindow=%s,%s}",
            getClass().getSimpleName(),
            hashCode(),
            getEndPoint().getLocalSocketAddress(),
            getEndPoint().getRemoteSocketAddress(),
            sendWindow,
            recvWindow,
            streamsState
        );
    }

    /**
     * <p>Listener for processable HTTP/2 frames that have been received.</p>
     * <p>Non-processable frames, such as those that caused a low-level
     * protocol error, or those that exceed frame rate control, are not
     * notified to instances of this class.</p>
     * <p>Applications can register instances of this class either
     * directly on the HTTP/2 session via
     * {@link HTTP2Session#addEventListener(EventListener)}, or by adding
     * the instances as beans to either the {@code HTTP2Client} (on the
     * client), or the HTTP/2 {@code ConnectionFactory} (on the server).
     * <p>Applications may invoke effect-free methods on the
     * {@link Session} object received in the methods of this class,
     * such as {@link Session#getRemoteSocketAddress()} or
     * {@link Session#getStreams()}, but should not invoke
     * {@link Session} methods that are effect-ful, such as
     * {@link Session#close(int, String, Callback)} or
     * {@link Session#newStream(HeadersFrame, Promise, Stream.Listener)},
     * since they may result in undefined behavior.</p>
     * <p>Instances of this class must be stateless or thread-safe,
     * since the same instance will be registered for all sessions.</p>
     * <p>Consider using {@link LifeCycleListener} if you need to
     * maintain per-session state.</p>
     */
    public interface FrameListener extends EventListener
    {
        /**
         * <p>Invoked when a processable HTTP/2 frame has been received.</p>
         *
         * @param session the associated HTTP/2 session
         * @param frame the HTTP/2 frame
         */
        default void onIncomingFrame(Session session, Frame frame)
        {
        }

        /**
         * <p>Invoked when a processable HTTP/2 frame is about to be sent.</p>
         *
         * @param session the associated HTTP/2 session
         * @param frame the HTTP/2 frame
         */
        default void onOutgoingFrame(Session session, Frame frame)
        {
        }
    }

    /**
     * <p>Listener for open/close {@link Session} events.</p>
     * <p>Applications can register instances of this class either
     * directly on the HTTP/2 session via
     * {@link HTTP2Session#addEventListener(EventListener)}, or by adding
     * the instances as beans to either the {@code HTTP2Client} (on the
     * client), or the HTTP/2 {@code ConnectionFactory} (on the server).
     * <p>Instances of this class must be stateless or thread-safe,
     * since the same instance will be registered for all sessions.</p>
     */
    public interface LifeCycleListener extends EventListener
    {
        /**
         * <p>Invoked when a session is opened.</p>
         *
         * @param session the associated HTTP/2 session
         */
        default void onOpen(Session session)
        {
        }

        /**
         * <p>Invoked when a session is closed.</p>
         *
         * @param session the associated HTTP/2 session
         */
        default void onClose(Session session)
        {
        }
    }

    public abstract static class Entry extends Callback.Nested
    {
        protected final Frame frame;
        protected final HTTP2Stream stream;

        protected Entry(Frame frame, HTTP2Stream stream, Callback callback)
        {
            super(callback);
            this.frame = frame;
            this.stream = stream;
        }

        public Frame frame()
        {
            return frame;
        }

        public abstract int getFrameBytesGenerated();

        public int getDataBytesRemaining()
        {
            return 0;
        }

        public abstract boolean generate(ByteBufferPool.Accumulator accumulator) throws HpackException;

        boolean hasHighPriority()
        {
            return false;
        }

        public void closeAndFail(Throwable failure)
        {
            if (stream != null)
            {
                stream.close();
                stream.getSession().removeStream(stream);
            }
            failed(failure);
        }

        public void resetAndFail(Throwable x)
        {
            if (stream != null)
                stream.reset(new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), Callback.from(() -> failed(x)));
        }

        /**
         * @return whether the entry should not be processed
         */
        public boolean shouldBeDropped()
        {
            switch (frame.getType())
            {
                // Frames of this type should not be dropped.
                case PRIORITY:
                case SETTINGS:
                case PING:
                case GO_AWAY:
                case WINDOW_UPDATE:
                case PREFACE:
                case DISCONNECT:
                    return false;
                // Frames of this type follow the logic below.
                case DATA:
                case HEADERS:
                case PUSH_PROMISE:
                case CONTINUATION:
                case RST_STREAM:
                    break;
                default:
                    throw new IllegalStateException();
            }

            // SPEC: section 6.4.
            if (frame.getType() == FrameType.RST_STREAM)
                return stream != null && stream.isLocal() && !stream.isCommitted();

            // Frames that do not have a stream associated are dropped.
            if (stream == null)
                return true;

            return stream.isResetOrFailed();
        }

        void commit()
        {
            if (stream != null)
                stream.commit();
        }

        @Override
        public String toString()
        {
            return frame.toString();
        }
    }

    private class ControlEntry extends Entry
    {
        private int frameBytes;

        private ControlEntry(Frame frame, HTTP2Stream stream, Callback callback)
        {
            super(frame, stream, callback);
        }

        @Override
        public int getFrameBytesGenerated()
        {
            return frameBytes;
        }

        @Override
        public boolean generate(ByteBufferPool.Accumulator accumulator) throws HpackException
        {
            frameBytes = generator.control(accumulator, frame);
            beforeSend();
            return true;
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
                case HEADERS ->
                {
                    HeadersFrame headersFrame = (HeadersFrame)frame;
                    stream.updateClose(headersFrame.isEndStream(), CloseState.Event.BEFORE_SEND);
                }
                case SETTINGS ->
                {
                    SettingsFrame settingsFrame = (SettingsFrame)frame;
                    if (!settingsFrame.isReply())
                        configure(settingsFrame.getSettings(), true);
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
                case HEADERS ->
                {
                    HeadersFrame headersFrame = (HeadersFrame)frame;
                    if (headersFrame.getMetaData().isRequest())
                        onStreamOpened(stream);
                    if (stream.updateClose(headersFrame.isEndStream(), CloseState.Event.AFTER_SEND))
                        removeStream(stream);
                }
                case WINDOW_UPDATE ->
                {
                    flowControl.windowUpdate(HTTP2Session.this, stream, (WindowUpdateFrame)frame);
                }
            }

            super.succeeded();
        }
    }

    private class DataEntry extends Entry
    {
        private int frameBytes;
        private int dataBytes;
        private int dataRemaining;

        private DataEntry(DataFrame frame, HTTP2Stream stream, Callback callback)
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
        public boolean generate(ByteBufferPool.Accumulator accumulator)
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
            int frameBytes = generator.data(accumulator, dataFrame, length);
            this.frameBytes += frameBytes;

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
        public void succeeded()
        {
            bytesWritten.addAndGet(frameBytes);
            frameBytes = 0;

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
            if (LOG.isDebugEnabled())
                LOG.debug("OnReset failed", x);
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
     * see <a href="https://tools.ietf.org/html/rfc7540#section-5.1.1">RFC 7540, 5.1.1</a>.</p>
     * <p>This implementation uses a queue to atomically reserve a stream id and claim
     * a slot in the queue; the slot is then assigned the entries to write.</p>
     * <p>Concurrent threads push slots in the queue but only one thread flushes
     * the slots, up to the slot that has a non-null entries to write, therefore
     * guaranteeing that frames are sent strictly in their stream id order.</p>
     * <p>This class also coordinates the creation of streams with the close of
     * the session, see
     * <a href="https://tools.ietf.org/html/rfc7540#section-6.8">RFC 7540, 6.8</a>.</p>
     */
    private class StreamsState
    {
        private final AutoLock lock = new AutoLock();
        private final Queue<Slot> slots = new ArrayDeque<>();
        // Must be incremented with the lock held.
        private final AtomicLong streamCount = new AtomicLong();
        private long idleNanoTime = NanoTime.now();
        private CloseState closed = CloseState.NOT_CLOSED;
        private Runnable zeroStreamsAction;
        private GoAwayFrame goAwayRecv;
        private GoAwayFrame goAwaySent;
        private Throwable failure;
        private Thread flushing;
        private CompletableFuture<Void> shutdownCallback;

        private CloseState getCloseState()
        {
            try (AutoLock ignored = lock.lock())
            {
                return closed;
            }
        }

        private CompletableFuture<Void> shutdown()
        {
            CompletableFuture<Void> future;
            try (AutoLock ignored = lock.lock())
            {
                if (shutdownCallback != null)
                    return shutdownCallback;
                if (closed == CloseState.CLOSED)
                    return CompletableFuture.completedFuture(null);
                shutdownCallback = future = new Callback.Completable();
            }
            goAway(GoAwayFrame.GRACEFUL, Callback.NOOP);
            return future;
        }

        private boolean goAway(GoAwayFrame frame, Callback callback)
        {
            boolean sendGoAway = false;
            boolean tryRunZeroStreamsAction = false;
            try (AutoLock ignored = lock.lock())
            {
                switch (closed)
                {
                    case NOT_CLOSED ->
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
                    }
                    case LOCALLY_CLOSED ->
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
                    }
                    case REMOTELY_CLOSED ->
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
                    }
                    default ->
                    {
                        // Already closing or closed, ignore it.
                        if (LOG.isDebugEnabled())
                            LOG.debug("Already closed, ignored {} for {}", frame, HTTP2Session.this);
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
            int error = ErrorCode.NO_ERROR.code;
            halt(error, reason, toFailure(error, reason));
        }

        private void halt(int error, String reason, Throwable cause)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Halting ({}) for {}", reason, HTTP2Session.this);
            GoAwayFrame goAwayFrame = null;
            GoAwayFrame goAwayFrameEvent;
            try (AutoLock ignored = lock.lock())
            {
                switch (closed)
                {
                    case NOT_CLOSED, REMOTELY_CLOSED, LOCALLY_CLOSED, CLOSING ->
                    {
                        if (goAwaySent == null || goAwaySent.isGraceful())
                            goAwaySent = goAwayFrame = newGoAwayFrame(ErrorCode.NO_ERROR.code, reason);
                        goAwayFrameEvent = goAwayRecv != null ? goAwayRecv : goAwaySent;
                        closed = CloseState.CLOSED;
                        zeroStreamsAction = null;
                        if (failure == null)
                            failure = cause;
                    }
                    default ->
                    {
                        return;
                    }
                }
            }

            GoAwayFrame goAway = goAwayFrame;
            failStreams(stream -> true, error, reason, cause, true, Callback.from(() ->
            {
                if (goAway != null)
                    sendGoAwayAndTerminate(goAway, goAwayFrameEvent);
                else
                    terminate(goAwayFrameEvent);
            }));
        }

        private void onGoAway(GoAwayFrame frame)
        {
            boolean failStreams = false;
            boolean tryRunZeroStreamsAction = false;
            try (AutoLock ignored = lock.lock())
            {
                switch (closed)
                {
                    case NOT_CLOSED ->
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
                    }
                    case LOCALLY_CLOSED ->
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
                    }
                    case REMOTELY_CLOSED ->
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
                    }
                    default ->
                    {
                        // Already closing or closed, ignore it.
                        if (LOG.isDebugEnabled())
                            LOG.debug("Already closed, ignored {} for {}", frame, HTTP2Session.this);
                    }
                }
            }

            notifyGoAway(HTTP2Session.this, frame);

            Callback callback = tryRunZeroStreamsAction ? Callback.from(this::tryRunZeroStreamsAction) : Callback.NOOP;
            if (failStreams)
            {
                // The lastStreamId carried by the GOAWAY is that of a local stream,
                // so the lastStreamId must be compared only to local streams ids.
                Predicate<Stream> failIf = stream -> stream.isLocal() && stream.getId() > frame.getLastStreamId();
                failStreams(failIf, frame.getError(), null, new RetryableStreamException(), false, callback);
            }
            else
            {
                callback.succeeded();
            }
        }

        private void onShutdown()
        {
            String reason = "input_shutdown";
            Throwable cause = null;
            boolean failStreams = false;
            try (AutoLock ignored = lock.lock())
            {
                switch (closed)
                {
                    case NOT_CLOSED, LOCALLY_CLOSED ->
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("Unexpected ISHUT for {}", HTTP2Session.this);
                        closed = CloseState.CLOSING;
                        failure = cause = new ClosedChannelException();
                    }
                    case REMOTELY_CLOSED ->
                    {
                        closed = CloseState.CLOSING;
                        GoAwayFrame goAwayFrame = newGoAwayFrame(ErrorCode.NO_ERROR.code, reason);
                        zeroStreamsAction = () -> terminate(goAwayFrame);
                        failure = new ClosedChannelException();
                        failStreams = true;
                    }
                    case CLOSING ->
                    {
                        if (failure == null)
                            failure = new ClosedChannelException();
                        failStreams = true;
                    }
                    default ->
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("Already closed, ignoring ISHUT for {}", HTTP2Session.this);
                        return;
                    }
                }
            }

            if (failStreams)
            {
                notifyFailure(HTTP2Session.this, cause, Callback.from(() ->
                {
                    // Since nothing else will arrive from the other peer, reset
                    // the streams for which the other peer did not send all frames.
                    Predicate<Stream> failIf = stream -> !stream.isRemotelyClosed();
                    failStreams(failIf, reason, false, Callback.from(this::tryRunZeroStreamsAction));
                }));
            }
            else
            {
                Throwable failure = cause;
                notifyFailure(HTTP2Session.this, failure, Callback.from(() ->
                    failStreams(stream -> true, ErrorCode.NO_ERROR.code, reason, failure, false, Callback.from(() ->
                        terminate(newGoAwayFrame(ErrorCode.NO_ERROR.code, reason))))));
            }
        }

        private boolean onIdleTimeout()
        {
            String reason = "idle_timeout";
            boolean notify = false;
            boolean terminate = false;
            boolean sendGoAway = false;
            GoAwayFrame goAwayFrame = null;
            Throwable cause = newTimeoutException();
            try (AutoLock ignored = lock.lock())
            {
                switch (closed)
                {
                    case NOT_CLOSED ->
                    {
                        long elapsed = NanoTime.millisSince(idleNanoTime);
                        if (elapsed < endPoint.getIdleTimeout())
                            return false;
                        notify = true;
                    }
                    case LOCALLY_CLOSED ->
                    {
                        // Timed out while waiting for closing events, fail all the streams.
                        if (goAwaySent.isGraceful())
                        {
                            goAwaySent = newGoAwayFrame(ErrorCode.NO_ERROR.code, reason);
                            sendGoAway = true;
                        }
                        goAwayFrame = goAwaySent;
                        closed = CloseState.CLOSING;
                        zeroStreamsAction = null;
                        failure = cause;
                    }
                    case REMOTELY_CLOSED ->
                    {
                        goAwaySent = newGoAwayFrame(ErrorCode.NO_ERROR.code, reason);
                        sendGoAway = true;
                        goAwayFrame = goAwaySent;
                        closed = CloseState.CLOSING;
                        zeroStreamsAction = null;
                        failure = cause;
                    }
                    default -> terminate = true;
                }
            }

            if (terminate)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Already closed, ignored idle timeout for {}", HTTP2Session.this);
                // Writes may be TCP congested, so termination never happened.
                flusher.abort(cause);
                return false;
            }

            if (notify)
            {
                boolean confirmed = notifyIdleTimeout(HTTP2Session.this);
                if (LOG.isDebugEnabled())
                    LOG.debug("Idle timeout {} for {}", confirmed ? "confirmed" : "ignored", HTTP2Session.this);
                if (confirmed)
                    halt(ErrorCode.CANCEL_STREAM_ERROR.code, reason, cause);
                return false;
            }

            boolean send = sendGoAway;
            GoAwayFrame frame = goAwayFrame;
            notifyFailure(HTTP2Session.this, cause, Callback.from(() ->
                failStreams(stream -> true, reason, true, Callback.from(() ->
                {
                    if (send)
                        sendGoAway(frame, Callback.from(() -> terminate(frame)));
                    else
                        terminate(frame);
                }))));
            return false;
        }

        private TimeoutException newTimeoutException()
        {
            return new TimeoutException("Session idle timeout expired");
        }

        private void onSessionFailure(int error, String reason, Callback callback)
        {
            GoAwayFrame goAwayFrame;
            Throwable cause;
            try (AutoLock ignored = lock.lock())
            {
                switch (closed)
                {
                    case NOT_CLOSED, LOCALLY_CLOSED, REMOTELY_CLOSED ->
                    {
                        // Send another GOAWAY with the error code.
                        goAwaySent = goAwayFrame = newGoAwayFrame(error, reason);
                        closed = CloseState.CLOSING;
                        zeroStreamsAction = null;
                        failure = cause = toFailure(error, reason);
                    }
                    default ->
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

            notifyFailure(HTTP2Session.this, cause, Callback.from(() ->
                failStreams(stream -> true, error, reason, toFailure(error, reason), true, Callback.from(() ->
                    sendGoAway(goAwayFrame, Callback.from(() ->
                        terminate(goAwayFrame)))))));
        }

        private void onWriteFailure(Throwable x)
        {
            String reason = "write_failure";
            try (AutoLock ignored = lock.lock())
            {
                switch (closed)
                {
                    case NOT_CLOSED, LOCALLY_CLOSED, REMOTELY_CLOSED ->
                    {
                        closed = CloseState.CLOSING;
                        failure = x;
                    }
                    default ->
                    {
                        return;
                    }
                }
            }

            if (LOG.isDebugEnabled())
                LOG.debug("Write failure {}", HTTP2Session.this, x);

            int error = ErrorCode.NO_ERROR.code;
            notifyFailure(HTTP2Session.this, x, Callback.from(() ->
                failStreams(stream -> true, error, reason, x, false, Callback.from(() ->
                {
                    GoAwayFrame goAwayFrame = newGoAwayFrame(error, reason);
                    terminate(goAwayFrame);
                }))));
        }

        private void sendGoAwayAndTerminate(GoAwayFrame frame, GoAwayFrame eventFrame)
        {
            sendGoAway(frame, Callback.from(() -> terminate(eventFrame)));
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
            try (AutoLock ignored = lock.lock())
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
                    case LOCALLY_CLOSED ->
                    {
                        if (goAwaySent.isGraceful())
                        {
                            action = zeroStreamsAction;
                            zeroStreamsAction = null;
                        }
                    }
                    case REMOTELY_CLOSED ->
                    {
                        if (goAwaySent != null && goAwaySent.isGraceful())
                        {
                            action = zeroStreamsAction;
                            zeroStreamsAction = null;
                        }
                    }
                    case CLOSING ->
                    {
                        closed = CloseState.CLOSED;
                        action = zeroStreamsAction;
                        zeroStreamsAction = null;
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

            CompletableFuture<Void> completable;
            try (AutoLock ignored = lock.lock())
            {
                completable = shutdownCallback;
            }
            if (completable != null)
                completable.complete(null);

            HTTP2Session.this.terminate(failure);
            notifyClose(HTTP2Session.this, frame, Callback.NOOP);
            notifyLifeCycleClose();
        }

        private int priority(PriorityFrame frame, Callback callback)
        {
            Slot slot = new Slot();
            int currentStreamId = frame.getStreamId();
            int streamId = reserveSlot(slot, currentStreamId, callback::failed);
            if (streamId <= 0)
                return 0;

            if (!priorityStreams.add(streamId))
            {
                callback.failed(new IllegalStateException("Duplicate stream " + streamId));
                return 0;
            }

            if (currentStreamId <= 0)
                frame = frame.withStreamId(streamId);
            slot.entries = List.of(newEntry(frame, null, Callback.from(callback::succeeded, x ->
            {
                removeStream(streamId);
                callback.failed(x);
            })));
            flush();
            return streamId;
        }

        private void newLocalStream(HTTP2Stream.FrameList frameList, Promise<Stream> promise, Stream.Listener listener)
        {
            Slot slot = new Slot();
            int currentStreamId = frameList.getStreamId();
            int streamId = reserveSlot(slot, currentStreamId, promise::failed);
            if (streamId <= 0)
                return;

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

        private Stream newUpgradeStream(HeadersFrame frame, Stream.Listener listener, Consumer<Throwable> failFn)
        {
            int streamId = localStreamIds.getAndAdd(2);
            HTTP2Session.this.onStreamCreated(streamId);
            HTTP2Stream stream = HTTP2Session.this.createLocalStream(streamId, (MetaData.Request)frame.getMetaData(), x ->
            {
                removeStream(streamId);
                failFn.accept(x);
            });
            if (stream != null)
            {
                stream.setListener(listener);
                stream.updateClose(frame.isEndStream(), CloseState.Event.AFTER_SEND);
            }
            return stream;
        }

        private boolean newRemoteStream(int streamId)
        {
            boolean created;
            try (AutoLock ignored = lock.lock())
            {
                created = switch (closed)
                {
                    case NOT_CLOSED -> true;
                    case LOCALLY_CLOSED ->
                        // SPEC: streams larger than GOAWAY's lastStreamId are dropped.
                        // Allow creation of streams that may have been in-flight.
                        streamId <= goAwaySent.getLastStreamId();
                    default -> false;
                };
            }
            if (created)
                HTTP2Session.this.onStreamCreated(streamId);
            return created;
        }

        private void push(PushPromiseFrame frame, Promise<Stream> promise, Stream.Listener listener)
        {
            Slot slot = new Slot();
            int streamId = reserveSlot(slot, frame.getPromisedStreamId(), promise::failed);
            if (streamId <= 0)
                return;

            frame = frame.withStreamId(streamId);
            if (createLocalStream(slot, Collections.singletonList(frame), promise, listener, streamId))
                return;
            freeSlot(slot, streamId);
        }

        private boolean createLocalStream(Slot slot, List<StreamFrame> frames, Promise<Stream> promise, Stream.Listener listener, int streamId)
        {
            MetaData.Request request = extractMetaDataRequest(frames.get(0));
            if (request == null)
                return false;
            HTTP2Stream stream = HTTP2Session.this.createLocalStream(streamId, request, promise::failed);
            if (stream == null)
                return false;

            stream.setListener(listener);
            stream.process(new PrefaceFrame(), Callback.NOOP);

            Callback streamCallback = Callback.from(Invocable.InvocationType.NON_BLOCKING, () -> promise.succeeded(stream), x ->
            {
                removeStream(stream);
                promise.failed(x);
            });
            int count = frames.size();
            if (count == 1)
            {
                slot.entries = List.of(newEntry(frames.get(0), stream, streamCallback));
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

        private MetaData.Request extractMetaDataRequest(StreamFrame frame)
        {
            if (frame instanceof HeadersFrame)
                return (MetaData.Request)((HeadersFrame)frame).getMetaData();
            if (frame instanceof PushPromiseFrame)
                return ((PushPromiseFrame)frame).getMetaData();
            return null;
        }

        private int reserveSlot(Slot slot, int streamId, Consumer<Throwable> fail)
        {
            if (streamId < 0 || (streamId > 0 && !isLocalStream(streamId)))
            {
                fail.accept(new IllegalArgumentException("invalid stream id " + streamId));
                return 0;
            }

            int maxTotal = getMaxTotalLocalStreams();

            boolean created = false;
            int reservedStreamId = 0;
            Throwable failure = null;
            try (AutoLock ignored = lock.lock())
            {
                // SPEC: cannot create new streams after receiving a GOAWAY.
                if (closed == CloseState.NOT_CLOSED)
                {
                    if (streamId == 0)
                    {
                        int total = incrementTotalLocalStreams(maxTotal);
                        if (total <= maxTotal)
                        {
                            // Stream id generated internally.
                            reservedStreamId = localStreamIds.getAndUpdate(v ->
                            {
                                if (v >= 0)
                                    return v + 2;
                                return v;
                            });
                            if (reservedStreamId > 0)
                            {
                                slots.offer(slot);
                                created = true;
                            }
                            else
                            {
                                failure = decrementTotalLocalStreams("max stream id exceeded");
                            }
                        }
                        else
                        {
                            failure = decrementTotalLocalStreams("max total streams exceeded");
                        }
                    }
                    else
                    {
                        // Stream id is given.
                        while (true)
                        {
                            int nextStreamId = localStreamIds.get();
                            if (nextStreamId > 0)
                            {
                                if (streamId >= nextStreamId)
                                {
                                    int total = incrementTotalLocalStreams(maxTotal);
                                    if (total <= maxTotal)
                                    {
                                        // This may overflow, but it's ok as the current streamId
                                        // is valid; it is the next streamId that will be invalid.
                                        int newNextStreamId = streamId + 2;
                                        if (localStreamIds.compareAndSet(nextStreamId, newNextStreamId))
                                        {
                                            reservedStreamId = streamId;
                                            slots.offer(slot);
                                            created = true;
                                            break;
                                        }
                                        else
                                        {
                                            totalLocalStreams.decrementAndGet();
                                        }
                                    }
                                    else
                                    {
                                        failure = decrementTotalLocalStreams("max total streams exceeded");
                                        break;
                                    }
                                }
                                else
                                {
                                    if (streams.containsKey(streamId) || priorityStreams.contains(streamId))
                                    {
                                        reservedStreamId = streamId;
                                        slots.offer(slot);
                                    }
                                    else
                                    {
                                        failure = new IllegalArgumentException("invalid stream id " + streamId);
                                    }
                                    break;
                                }
                            }
                            else
                            {
                                reservedStreamId = nextStreamId;
                                failure = new IllegalStateException("max stream id exceeded");
                                break;
                            }
                        }
                    }
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
                if (created)
                    HTTP2Session.this.onStreamCreated(reservedStreamId);
            }
            else
            {
                fail.accept(failure);
            }
            return reservedStreamId;
        }

        private void freeSlot(Slot slot, int streamId)
        {
            try (AutoLock ignored = lock.lock())
            {
                slots.remove(slot);
            }
            HTTP2Session.this.onStreamDestroyed(streamId);
            flush();
        }

        private int incrementTotalLocalStreams(int maxTotal)
        {
            return totalLocalStreams.updateAndGet(v ->
            {
                if (v <= maxTotal)
                    return v + 1;
                return v;
            });
        }

        private Throwable decrementTotalLocalStreams(String message)
        {
            totalLocalStreams.decrementAndGet();
            return new IllegalStateException(message);
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
                List<Entry> entries;
                try (AutoLock ignored = lock.lock())
                {
                    if (flushing == null)
                        flushing = thread;
                    else if (flushing != thread)
                        return; // Another thread is flushing.

                    Slot slot = slots.peek();
                    entries = slot == null ? null : slot.entries;

                    if (entries == null)
                    {
                        flushing = null;
                        // No more slots or null entries, so we may iterate on the flusher.
                        break;
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
            try (AutoLock ignored = lock.lock())
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

        private static class Slot
        {
            private volatile List<Entry> entries;
        }
    }

    private class StreamTimeouts extends CyclicTimeouts<HTTP2Stream>
    {
        private StreamTimeouts(Scheduler scheduler)
        {
            super(scheduler);
        }

        @Override
        protected Iterator<HTTP2Stream> iterator()
        {
            return streams.values().stream().map(HTTP2Stream.class::cast).iterator();
        }

        @Override
        protected boolean onExpired(HTTP2Stream stream)
        {
            stream.onIdleTimeout(new TimeoutException("Idle timeout " + stream.getIdleTimeout() + " ms elapsed"));
            // The implementation of the Iterator returned above does not support
            // removal, but the HTTP2Stream will be removed by stream.onIdleTimeout().
            return false;
        }
    }
}
