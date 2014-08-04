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

import java.io.EOFException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
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
import org.eclipse.jetty.util.ArrayQueue;
import org.eclipse.jetty.util.Atomics;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;
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
    private final AtomicInteger windowSize = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Scheduler scheduler;
    private final EndPoint endPoint;
    private final Generator generator;
    private final Listener listener;
    private final FlowControl flowControl;
    private final Flusher flusher;
    private int maxLocalStreams;
    private int maxRemoteStreams;

    public HTTP2Session(Scheduler scheduler, EndPoint endPoint, Generator generator, Listener listener, FlowControl flowControl, int maxStreams, int initialStreamId)
    {
        this.scheduler = scheduler;
        this.endPoint = endPoint;
        this.generator = generator;
        this.listener = listener;
        this.flowControl = flowControl;
        this.flusher = new Flusher(4);
        this.maxLocalStreams = maxStreams;
        this.maxRemoteStreams = maxStreams;
        this.streamIds.set(initialStreamId);
        this.windowSize.set(flowControl.getInitialWindowSize());
    }

    public int getMaxRemoteStreams()
    {
        return maxRemoteStreams;
    }

    public void setMaxRemoteStreams(int maxRemoteStreams)
    {
        this.maxRemoteStreams = maxRemoteStreams;
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
            flowControl.onDataReceived(this, stream, flowControlLength);
            boolean result = stream.process(frame, new Callback.Adapter()
            {
                @Override
                public void succeeded()
                {
                    flowControl.onDataConsumed(HTTP2Session.this, stream, flowControlLength);
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
        if (LOG.isDebugEnabled())
            LOG.debug("Received {}", frame);

        if (frame.isReply())
            return false;

        Map<Integer, Integer> settings = frame.getSettings();
        if (settings.containsKey(SettingsFrame.HEADER_TABLE_SIZE))
        {
            int headerTableSize = settings.get(SettingsFrame.HEADER_TABLE_SIZE);
            if (LOG.isDebugEnabled())
                LOG.debug("Updated HPACK header table size to {}", headerTableSize);
            generator.setHeaderTableSize(headerTableSize);
        }
        if (settings.containsKey(SettingsFrame.MAX_CONCURRENT_STREAMS))
        {
            maxLocalStreams = settings.get(SettingsFrame.MAX_CONCURRENT_STREAMS);
            if (LOG.isDebugEnabled())
                LOG.debug("Updated max local concurrent streams to {}", maxLocalStreams);
        }
        if (settings.containsKey(SettingsFrame.INITIAL_WINDOW_SIZE))
        {
            int windowSize = settings.get(SettingsFrame.INITIAL_WINDOW_SIZE);
            flowControl.updateInitialWindowSize(this, windowSize);
        }
        if (settings.containsKey(SettingsFrame.MAX_FRAME_SIZE))
        {
            int maxFrameSize = settings.get(SettingsFrame.MAX_FRAME_SIZE);
            // Spec: check the max frame size is sane.
            if (maxFrameSize < Frame.DEFAULT_MAX_LENGTH || maxFrameSize > Frame.MAX_MAX_LENGTH)
            {
                onConnectionFailure(ErrorCodes.PROTOCOL_ERROR, "invalid_settings_max_frame_size");
                return false;
            }
            if (LOG.isDebugEnabled())
                LOG.debug("Updated max frame size to {}", maxFrameSize);
            generator.setMaxFrameSize(maxFrameSize);
        }
        notifySettings(this, frame);

        // SPEC: SETTINGS frame MUST be replied.
        SettingsFrame reply = new SettingsFrame(Collections.<Integer, Integer>emptyMap(), true);
        settings(reply, disconnectOnFailure());
        return false;
    }

    @Override
    public boolean onPushPromise(PushPromiseFrame frame)
    {
        // TODO
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
            control(null, reply, disconnectOnFailure());
        }
        return false;
    }

    @Override
    public boolean onGoAway(GoAwayFrame frame)
    {
        if (LOG.isDebugEnabled())
        {
            String reason = tryConvertPayload(frame.getPayload());
            if (LOG.isDebugEnabled())
                LOG.debug("Received {}: {}/'{}'", frame.getType(), frame.getError(), reason);
        }

        flusher.close();
        disconnect();

        notifyClose(this, frame);

        return false;
    }

    private String tryConvertPayload(byte[] payload)
    {
        if (payload == null)
            return "";
        ByteBuffer buffer = BufferUtil.toBuffer(payload);
        try
        {
            return BufferUtil.toUTF8String(buffer);
        }
        catch (Throwable x)
        {
            return BufferUtil.toDetailString(buffer);
        }
    }

    @Override
    public boolean onWindowUpdate(WindowUpdateFrame frame)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Received {}", frame);
        int streamId = frame.getStreamId();
        IStream stream = null;
        if (streamId > 0)
            stream = getStream(streamId);
        flowControl.onWindowUpdate(this, stream, frame);

        // Flush stalled data.
        flusher.iterate();
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
            final IStream stream = createLocalStream(frame, promise);
            if (stream == null)
                return;
            stream.updateClose(frame.isEndStream(), true);
            stream.setListener(listener);

            FlusherEntry entry = new FlusherEntry(stream, frame, new PromiseCallback<>(promise, stream));
            flusher.append(entry);
        }
        // Iterate outside the synchronized block.
        flusher.iterate();
    }

    @Override
    public void settings(SettingsFrame frame, Callback callback)
    {
        control(null, frame, callback);
    }

    @Override
    public void ping(PingFrame frame, Callback callback)
    {
        if (frame.isReply())
            callback.failed(new IllegalArgumentException());
        else
            control(null, frame, callback);
    }

    @Override
    public void reset(ResetFrame frame, Callback callback)
    {
        if (closed.get())
            callback.succeeded();
        else
            control(getStream(frame.getStreamId()), frame, callback);
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
            control(null, frame, callback);
        }
    }

    @Override
    public void control(IStream stream, Frame frame, Callback callback)
    {
        // We want to generate as late as possible to allow re-prioritization.
        frame(new FlusherEntry(stream, frame, callback));
    }

    @Override
    public void data(IStream stream, DataFrame frame, Callback callback)
    {
        // We want to generate as late as possible to allow re-prioritization.
        frame(new DataFlusherEntry(stream, frame, callback));
    }

    private void frame(FlusherEntry entry)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Sending {}", entry.frame);
        // Ping frames are prepended to process them as soon as possible.
        if (entry.frame.getType() == FrameType.PING)
            flusher.prepend(entry);
        else
            flusher.append(entry);
        flusher.iterate();
    }

    protected IStream createLocalStream(HeadersFrame frame, Promise<Stream> promise)
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

        IStream stream = newStream(frame);
        int streamId = stream.getId();
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

    protected IStream createRemoteStream(HeadersFrame frame)
    {
        int streamId = frame.getStreamId();

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

        IStream stream = newStream(frame);

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

    protected IStream newStream(HeadersFrame frame)
    {
        return new HTTP2Stream(scheduler, this, frame);
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

    public IStream getStream(int streamId)
    {
        return streams.get(streamId);
    }

    protected int getWindowSize()
    {
        return windowSize.get();
    }

    @Override
    public int updateWindowSize(int delta)
    {
        return windowSize.getAndAdd(delta);
    }

    @Override
    public void shutdown()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Shutting down");

        // Append a fake FlusherEntry that disconnects when the queue is drained.
        flusher.append(new ShutdownFlusherEntry());
        flusher.iterate();
    }

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
        return String.format("%s@%x{queueSize=%d,windowSize=%s,streams=%d}", getClass().getSimpleName(),
                hashCode(), flusher.getQueueSize(), windowSize, streams.size());
    }

    private class Flusher extends IteratingCallback
    {
        private final ArrayQueue<FlusherEntry> queue = new ArrayQueue<>(ArrayQueue.DEFAULT_CAPACITY, ArrayQueue.DEFAULT_GROWTH);
        private final Map<IStream, Integer> streams = new HashMap<>();
        private final List<FlusherEntry> reset = new ArrayList<>();
        private final ByteBufferPool.Lease lease = new ByteBufferPool.Lease(generator.getByteBufferPool());
        private final int maxGather;
        private final List<FlusherEntry> active;
        private final Queue<FlusherEntry> complete;

        private Flusher(int maxGather)
        {
            this.maxGather = maxGather;
            this.active = new ArrayList<>(maxGather);
            this.complete = new ArrayDeque<>(maxGather);
        }

        private void append(FlusherEntry entry)
        {
            boolean fail = false;
            synchronized (queue)
            {
                if (isClosed())
                    fail = true;
                else
                    queue.offer(entry);
                if (LOG.isDebugEnabled())
                    LOG.debug("Appended {}, queue={}", entry, queue.size());
            }
            if (fail)
                closed(entry);
        }

        private void prepend(FlusherEntry entry)
        {
            boolean fail = false;
            synchronized (queue)
            {
                if (isClosed())
                    fail = true;
                else
                    queue.add(0, entry);
            }
            if (fail)
                closed(entry);
        }

        private int getQueueSize()
        {
            synchronized (queue)
            {
                return queue.size();
            }
        }

        @Override
        protected Action process() throws Exception
        {
            synchronized (queue)
            {
                int sessionWindow = getWindowSize();
                int nonStalledIndex = 0;
                int size = queue.size();
                while (nonStalledIndex < size)
                {
                    FlusherEntry entry = queue.get(nonStalledIndex);
                    IStream stream = entry.stream;
                    int remaining = 0;
                    if (entry.frame instanceof DataFrame)
                    {
                        DataFrame dataFrame = (DataFrame)entry.frame;
                        remaining = dataFrame.remaining();
                        if (remaining > 0)
                        {
                            // Is the session stalled ?
                            if (sessionWindow <= 0)
                            {
                                flowControl.onSessionStalled(HTTP2Session.this);
                                ++nonStalledIndex;
                                // There may be *non* flow controlled frames to send.
                                continue;
                            }

                            if (stream != null)
                            {
                                Integer streamWindow = streams.get(stream);
                                if (streamWindow == null)
                                {
                                    streamWindow = stream.getWindowSize();
                                    streams.put(stream, streamWindow);
                                }

                                // Is it a frame belonging to an already stalled stream ?
                                if (streamWindow <= 0)
                                {
                                    flowControl.onStreamStalled(stream);
                                    ++nonStalledIndex;
                                    continue;
                                }
                            }
                        }
                    }

                    // We will be possibly writing this frame.
                    queue.remove(nonStalledIndex);
                    --size;

                    // If the stream has been reset, don't send flow controlled frames.
                    if (stream != null && stream.isReset() && remaining > 0)
                    {
                        reset.add(entry);
                        continue;
                    }

                    // Reduce the flow control windows.
                    sessionWindow -= remaining;
                    if (stream != null && remaining > 0)
                        streams.put(stream, streams.get(stream) - remaining);

                    active.add(entry);
                    if (active.size() == maxGather)
                        break;
                }
                streams.clear();
            }

            for (int i = 0; i < reset.size(); ++i)
            {
                FlusherEntry entry = reset.get(i);
                entry.reset();
            }
            reset.clear();

            if (active.isEmpty())
                return Action.IDLE;

            for (int i = 0; i < active.size(); ++i)
            {
                FlusherEntry entry = active.get(i);
                entry.generate(lease);
            }

            List<ByteBuffer> byteBuffers = lease.getByteBuffers();
            if (LOG.isDebugEnabled())
                LOG.debug("Writing {} buffers ({} bytes) for {} frames {}", byteBuffers.size(), lease.getTotalLength(), active.size(), active);
            endPoint.write(this, byteBuffers.toArray(new ByteBuffer[byteBuffers.size()]));
            return Action.SCHEDULED;
        }

        @Override
        public void succeeded()
        {
            lease.recycle();

            // Transfer active items to avoid reentrancy.
            for (int i = 0; i < active.size(); ++i)
                complete.add(active.get(i));
            active.clear();

            if (LOG.isDebugEnabled())
                LOG.debug("Written {} frames for {}", complete.size(), complete);

            // Drain the queue one by one to avoid reentrancy.
            while (!complete.isEmpty())
            {
                FlusherEntry entry = complete.poll();
                entry.succeeded();
            }

            super.succeeded();
        }

        @Override
        protected void onCompleteSuccess()
        {
            throw new IllegalStateException();
        }

        @Override
        protected void onCompleteFailure(Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug(x);

            lease.recycle();

            // Transfer active items to avoid reentrancy.
            for (int i = 0; i < active.size(); ++i)
                complete.add(active.get(i));
            active.clear();

            // Drain the queue one by one to avoid reentrancy.
            while (!complete.isEmpty())
            {
                FlusherEntry entry = complete.poll();
                entry.failed(x);
            }
        }

        public void close()
        {
            super.close();

            Queue<FlusherEntry> queued;
            synchronized (queue)
            {
                queued = new ArrayDeque<>(queue);
                queue.clear();
            }        

            if (LOG.isDebugEnabled())
                LOG.debug("Closing, queued={}", queued.size());
            
            for (FlusherEntry item : queued)
                closed(item);
        }

        protected void closed(FlusherEntry item)
        {
            item.failed(new ClosedChannelException());
        }
    }

    private class FlusherEntry implements Callback
    {
        protected final IStream stream;
        protected final Frame frame;
        protected final Callback callback;

        private FlusherEntry(IStream stream, Frame frame, Callback callback)
        {
            this.stream = stream;
            this.frame = frame;
            this.callback = callback;
        }

        public void generate(ByteBufferPool.Lease lease)
        {
            try
            {
                generator.control(lease, frame);
                if (LOG.isDebugEnabled())
                    LOG.debug("Generated {}", frame);
            }
            catch (Throwable x)
            {
                LOG.debug("Frame generation failure", x);
                failed(x);
            }
        }

        public void reset()
        {
            callback.failed(new EOFException("reset"));
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
                    disconnect();
                    break;
                }
                default:
                {
                    break;
                }
            }
            callback.succeeded();
        }

        @Override
        public void failed(Throwable x)
        {
            if (stream != null)
                stream.close();
            close(ErrorCodes.INTERNAL_ERROR, "generator_error", Adapter.INSTANCE);
            callback.failed(x);
        }

        @Override
        public String toString()
        {
            return frame.toString();
        }
    }

    private class DataFlusherEntry extends FlusherEntry
    {
        private int length;

        private DataFlusherEntry(IStream stream, DataFrame frame, Callback callback)
        {
            super(stream, frame, callback);
        }

        public void generate(ByteBufferPool.Lease lease)
        {
            DataFrame dataFrame = (DataFrame)frame;
            int windowSize = stream.getWindowSize();
            int frameLength = dataFrame.remaining();
            this.length = Math.min(frameLength, windowSize);
            generator.data(lease, dataFrame, length);
            if (LOG.isDebugEnabled())
                LOG.debug("Generated {}, maxLength={}", dataFrame, length);
        }

        @Override
        public void succeeded()
        {
            flowControl.onDataSent(HTTP2Session.this, stream, length);
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

    private class ShutdownFlusherEntry extends FlusherEntry
    {
        public ShutdownFlusherEntry()
        {
            super(null, null, Adapter.INSTANCE);
        }

        @Override
        public void generate(ByteBufferPool.Lease lease)
        {
        }

        @Override
        public void succeeded()
        {
            flusher.close();
            disconnect();
        }

        @Override
        public void failed(Throwable x)
        {
            flusher.close();
            disconnect();
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x", "ShutdownFrame", hashCode());
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
