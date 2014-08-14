//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.DataFrame;
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
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Scheduler;

public abstract class HTTP2Session implements ISession, Parser.Listener
{
    private static final Logger LOG = Log.getLogger(HTTP2Session.class);

    private final Callback disconnectOnFailure = new Callback.Adapter()
    {
        @Override
        public void failed(Throwable x)
        {
            disconnect();
        }
    };
    private final ConcurrentMap<Integer, IStream> streams = new ConcurrentHashMap<>();
    private final AtomicInteger streamIds = new AtomicInteger();
    private final AtomicInteger lastStreamId = new AtomicInteger();
    private final AtomicInteger localStreamCount = new AtomicInteger();
    private final AtomicInteger remoteStreamCount = new AtomicInteger();
    private final AtomicInteger sendWindow = new AtomicInteger();
    private final AtomicInteger recvWindow = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Scheduler scheduler;
    private final EndPoint endPoint;
    private final Generator generator;
    private final Listener listener;
    private final FlowControl flowControl;
    private final HTTP2Flusher flusher;
    private int maxLocalStreams;
    private int maxRemoteStreams;

    public HTTP2Session(Scheduler scheduler, EndPoint endPoint, Generator generator, Listener listener, FlowControl flowControl, int maxStreams, int initialStreamId)
    {
        this.scheduler = scheduler;
        this.endPoint = endPoint;
        this.generator = generator;
        this.listener = listener;
        this.flowControl = flowControl;
        this.flusher = new HTTP2Flusher(this);
        this.maxLocalStreams = maxStreams;
        this.maxRemoteStreams = maxStreams;
        this.streamIds.set(initialStreamId);
        this.sendWindow.set(FlowControl.DEFAULT_WINDOW_SIZE);
        this.recvWindow.set(FlowControl.DEFAULT_WINDOW_SIZE);
    }

    public FlowControl getFlowControl()
    {
        return flowControl;
    }

    public int getMaxRemoteStreams()
    {
        return maxRemoteStreams;
    }

    public void setMaxRemoteStreams(int maxRemoteStreams)
    {
        this.maxRemoteStreams = maxRemoteStreams;
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
    public boolean onData(final DataFrame frame)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Received {}", frame);
        int streamId = frame.getStreamId();
        final IStream stream = getStream(streamId);
        if (stream != null)
        {
            stream.updateClose(frame.isEndStream(), false);

            // The flow control length includes the padding bytes.
            final int flowControlLength = frame.remaining() + frame.padding();
            flowControl.onDataReceived(stream, flowControlLength);

            if (getRecvWindow() < 0)
            {
                close(ErrorCodes.FLOW_CONTROL_ERROR, "session_window_exceeded", disconnectOnFailure);
                return false;
            }

            boolean result = stream.process(frame, new Callback.Adapter()
            {
                @Override
                public void succeeded()
                {
                    flowControl.onDataConsumed(stream, flowControlLength);
                }
            });

            if (stream.isClosed())
                removeStream(stream, false);
            return result;
        }
        else
        {
            ResetFrame resetFrame = new ResetFrame(streamId, ErrorCodes.STREAM_CLOSED_ERROR);
            reset(resetFrame, disconnectOnFailure());
            return false;
        }
    }

    @Override
    public abstract boolean onHeaders(HeadersFrame frame);

    @Override
    public boolean onPriority(PriorityFrame frame)
    {
        return false;
    }

    @Override
    public boolean onReset(ResetFrame frame)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Received {}", frame);

        IStream stream = getStream(frame.getStreamId());
        if (stream != null)
            stream.process(frame, Callback.Adapter.INSTANCE);

        notifyReset(this, frame);

        if (stream != null)
            removeStream(stream, false);

        return false;
    }

    @Override
    public boolean onSettings(SettingsFrame frame)
    {
        if (frame.isReply())
            return false;

        // Iterate over all settings
        for (Map.Entry<Integer, Integer> entry : frame.getSettings().entrySet())
        {
            int value=entry.getValue();
            switch (entry.getKey())
            {
                case SettingsFrame.HEADER_TABLE_SIZE:
                    if (LOG.isDebugEnabled())
                        LOG.debug("Update HPACK header table size to {}", value);
                    generator.setHeaderTableSize(value);
                    break;
                    
                case SettingsFrame.ENABLE_PUSH:
                    break;
                    
                case SettingsFrame.MAX_CONCURRENT_STREAMS:
                    maxLocalStreams = value;
                    if (LOG.isDebugEnabled())
                        LOG.debug("Update max local concurrent streams to {}", maxLocalStreams);
                    break;
                    
                case SettingsFrame.INITIAL_WINDOW_SIZE:
                    if (LOG.isDebugEnabled())
                        LOG.debug("Update initial window size to {}", value);
                    flowControl.updateInitialStreamWindow(this, value);
                    break;
                    
                case SettingsFrame.MAX_FRAME_SIZE:
                    if (LOG.isDebugEnabled())
                        LOG.debug("Update max frame size to {}", value);
                    // SPEC: check the max frame size is sane.
                    if (value < Frame.DEFAULT_MAX_LENGTH || value > Frame.MAX_MAX_LENGTH)
                    {
                        onConnectionFailure(ErrorCodes.PROTOCOL_ERROR, "invalid_settings_max_frame_size");
                        return false;
                    }
                    generator.setMaxFrameSize(value);

                    break;
                    
                case SettingsFrame.MAX_HEADER_LIST_SIZE:
                    // TODO implement
                    LOG.warn("NOT IMPLEMENTED max header list size to {}", value);
                    break;
                    
                default:
                    LOG.debug("Unknown setting {}:{}",entry.getKey(),value);
            }
        }
        notifySettings(this, frame);

        // SPEC: SETTINGS frame MUST be replied.
        SettingsFrame reply = new SettingsFrame(Collections.<Integer, Integer>emptyMap(), true);
        settings(reply, disconnectOnFailure());
        return false;
    }

    @Override
    public boolean onPing(PingFrame frame)
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
            control(null, disconnectOnFailure(), reply);
        }
        return false;
    }

    @Override
    public boolean onGoAway(GoAwayFrame frame)
    {
        if (LOG.isDebugEnabled())
        {
            String reason = frame.tryConvertPayload();
            if (LOG.isDebugEnabled())
                LOG.debug("Received {}: {}/'{}'", frame.getType(), frame.getError(), reason);
        }

        flusher.close();

        notifyClose(this, frame);

        return false;
    }

    @Override
    public boolean onWindowUpdate(WindowUpdateFrame frame)
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
        return false;
    }

    @Override
    public void onConnectionFailure(int error, String reason)
    {
        close(error, reason, disconnectOnFailure());
    }

    @Override
    public void newStream(HeadersFrame frame, Promise<Stream> promise, Stream.Listener listener)
    {
        // Synchronization is necessary to atomically create
        // the stream id and enqueue the frame to be sent.
        synchronized (this)
        {
            int streamId = streamIds.getAndAdd(2);
            PriorityFrame priority = frame.getPriority();
            priority = priority == null ? null : new PriorityFrame(streamId, priority.getDependentStreamId(),
                    priority.getWeight(), priority.isExclusive());
            frame = new HeadersFrame(streamId, frame.getMetaData(), priority, frame.isEndStream());
            final IStream stream = createLocalStream(streamId, promise);
            if (stream == null)
                return;
            stream.updateClose(frame.isEndStream(), true);
            stream.setListener(listener);

            ControlEntry entry = new ControlEntry(frame, stream, new PromiseCallback<>(promise, stream));
            flusher.append(entry);
        }
        // Iterate outside the synchronized block.
        flusher.iterate();
    }

    @Override
    public void push(IStream stream, Promise<Stream> promise, PushPromiseFrame frame)
    {
        // Synchronization is necessary to atomically create
        // the stream id and enqueue the frame to be sent.
        synchronized (this)
        {
            int streamId = streamIds.getAndAdd(2);
            frame = new PushPromiseFrame(frame.getStreamId(), streamId, frame.getMetaData());

            final IStream pushStream = createLocalStream(streamId, promise);
            if (pushStream == null)
                return;
            pushStream.updateClose(true, false);

            ControlEntry entry = new ControlEntry(frame, pushStream, new PromiseCallback<>(promise, pushStream));
            flusher.append(entry);
        }
        // Iterate outside the synchronized block.
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

    @Override
    public void reset(ResetFrame frame, Callback callback)
    {
        control(getStream(frame.getStreamId()), callback, frame);
    }

    @Override
    public void close(int error, String reason, Callback callback)
    {
        if (closed.compareAndSet(false, true))
        {
            byte[] payload = reason == null ? null : reason.getBytes(StandardCharsets.UTF_8);
            GoAwayFrame frame = new GoAwayFrame(lastStreamId.get(), error, payload);
            if (LOG.isDebugEnabled())
                LOG.debug("Sending {}: {}", frame.getType(), reason);
            control(null, callback, frame);
        }
    }

    private void control(IStream stream, Callback callback, Frame frame)
    {
        control(stream, callback, frame, Frame.EMPTY_ARRAY);
    }

    @Override
    public void control(IStream stream, Callback callback, Frame frame, Frame... frames)
    {
        // We want to generate as late as possible to allow re-prioritization.
        int length = frames.length;
        frame(new ControlEntry(frame, stream, callback), length == 0);
        for (int i = 1; i <= length; ++i)
            frame(new ControlEntry(frames[i - 1], stream, callback), i == length);
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
        if (entry.frame.getType() == FrameType.PING)
            flusher.prepend(entry);
        else
            flusher.append(entry);
        if (flush)
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

        IStream stream = newStream(streamId);
        if (streams.putIfAbsent(streamId, stream) == null)
        {
            stream.setIdleTimeout(endPoint.getIdleTimeout());
            flowControl.onNewStream(stream);
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
                reset(new ResetFrame(streamId, ErrorCodes.REFUSED_STREAM_ERROR), disconnectOnFailure());
                return null;
            }
            if (remoteStreamCount.compareAndSet(remoteCount, remoteCount + 1))
                break;
        }

        IStream stream = newStream(streamId);

        // SPEC: duplicate stream is treated as connection error.
        if (streams.putIfAbsent(streamId, stream) == null)
        {
            updateLastStreamId(streamId);
            stream.setIdleTimeout(endPoint.getIdleTimeout());
            flowControl.onNewStream(stream);
            if (LOG.isDebugEnabled())
                LOG.debug("Created remote {}", stream);
            return stream;
        }
        else
        {
            close(ErrorCodes.PROTOCOL_ERROR, "duplicate_stream", disconnectOnFailure());
            return null;
        }
    }

    protected IStream newStream(int streamId)
    {
        return new HTTP2Stream(scheduler, this, streamId);
    }

    protected void removeStream(IStream stream, boolean local)
    {
        IStream removed = streams.remove(stream.getId());
        if (removed != null)
        {
            assert removed == stream;

            if (local)
                localStreamCount.decrementAndGet();
            else
                remoteStreamCount.decrementAndGet();

            if (LOG.isDebugEnabled())
                LOG.debug("Removed {}", stream);
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

    protected int getSendWindow()
    {
        return sendWindow.get();
    }

    protected int getRecvWindow()
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
    public void shutdown()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Shutting down");
        flusher.close();
    }

    @Override
    public void disconnect()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Disconnecting");
        endPoint.close();
    }

    private void updateLastStreamId(int streamId)
    {
        Atomics.updateMax(lastStreamId, streamId);
    }

    protected Callback disconnectOnFailure()
    {
        return disconnectOnFailure;
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

    @Override
    public String toString()
    {
        return String.format("%s@%x{queueSize=%d,sendWindow=%s,recvWindow=%s,streams=%d}", getClass().getSimpleName(),
                hashCode(), flusher.getQueueSize(), sendWindow, recvWindow, streams.size());
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
            switch (frame.getType())
            {
                case RST_STREAM:
                {
                    if (stream != null)
                        removeStream(stream, true);
                    break;
                }
                case GO_AWAY:
                {
                    flusher.close();
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

                int streamSendWindow = stream.getSendWindow();
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
                // We need to keep the correct ordering of frames, to avoid that other
                // frames for the same stream are written before this one is finished.
                flusher.prepend(this);
            }
            else
            {
                // Only now we can update the close state
                // and eventually remove the stream.
                stream.updateClose(dataFrame.isEndStream(), true);
                if (stream.isClosed())
                    removeStream(stream, true);
                callback.succeeded();
            }
        }
    }

    private class PromiseCallback<C> implements Callback
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
