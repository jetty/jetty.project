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

import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.GoAwayFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PingFrame;
import org.eclipse.jetty.http2.frames.PriorityFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.http2.frames.WindowUpdateFrame;
import org.eclipse.jetty.http2.generator.Generator;
import org.eclipse.jetty.http2.parser.ErrorCode;
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

public abstract class HTTP2Session implements ISession, Parser.Listener
{
    private static final Logger LOG = Log.getLogger(HTTP2Session.class);

    protected final Callback disconnectCallback = new Callback.Adapter()
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
    private final AtomicInteger streamCount = new AtomicInteger();
    private final AtomicInteger windowSize = new AtomicInteger();
    private final EndPoint endPoint;
    private final Generator generator;
    private final Listener listener;
    private final FlowControl flowControl;
    private final Flusher flusher;
    private volatile int maxStreamCount;

    public HTTP2Session(EndPoint endPoint, Generator generator, Listener listener, FlowControl flowControl, int maxStreams, int initialStreamId)
    {
        this.endPoint = endPoint;
        this.generator = generator;
        this.listener = listener;
        this.flowControl = flowControl;
        this.flusher = new Flusher(4);
        this.maxStreamCount = maxStreams;
        this.streamIds.set(initialStreamId);
        this.windowSize.set(flowControl.getInitialWindowSize());
    }

    public Generator getGenerator()
    {
        return generator;
    }

    public int getMaxStreamCount()
    {
        return maxStreamCount;
    }

    public FlowControl getFlowControl()
    {
        return flowControl;
    }

    @Override
    public boolean onData(final DataFrame frame)
    {
        int streamId = frame.getStreamId();
        final IStream stream = getStream(streamId);
        if (stream != null)
        {
            stream.updateClose(frame.isEndStream(), false);
            final int length = frame.remaining();
            flowControl.onDataReceived(this, stream, length);
            return stream.process(frame, new Callback.Adapter()
            {
                @Override
                public void succeeded()
                {
                    flowControl.onDataConsumed(HTTP2Session.this, stream, length);
                }
            });
        }
        else
        {
            ResetFrame resetFrame = new ResetFrame(streamId, ErrorCode.STREAM_CLOSED_ERROR);
            reset(resetFrame, disconnectCallback);
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
        return false;
    }

    @Override
    public boolean onSettings(SettingsFrame frame)
    {
        Map<Integer, Integer> settings = frame.getSettings();
        if (settings.containsKey(SettingsFrame.MAX_CONCURRENT_STREAMS))
        {
            maxStreamCount = settings.get(SettingsFrame.MAX_CONCURRENT_STREAMS);
            LOG.debug("Updated max concurrent streams to {}", maxStreamCount);
        }
        if (settings.containsKey(SettingsFrame.INITIAL_WINDOW_SIZE))
        {
            int windowSize = settings.get(SettingsFrame.INITIAL_WINDOW_SIZE);
            flowControl.updateInitialWindowSize(this, windowSize);
            LOG.debug("Updated initial window size to {}", windowSize);
        }
        // TODO: handle other settings
        notifySettings(this, frame);
        return false;
    }

    @Override
    public boolean onPing(PingFrame frame)
    {
        return false;
    }

    @Override
    public boolean onGoAway(GoAwayFrame frame)
    {
        if (LOG.isDebugEnabled())
        {
            String reason = tryConvertPayload(frame.getPayload());
            LOG.debug("Received {}: {}/'{}'", frame.getType(), frame.getError(), reason);
        }

        flusher.close();
        disconnect();
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
        close(error, reason, disconnectCallback);
    }

    @Override
    public void newStream(HeadersFrame frame, final Promise<Stream> promise, Stream.Listener listener)
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
            final IStream stream = createLocalStream(frame);
            if (stream == null)
            {
                promise.failed(new IllegalStateException());
                return;
            }
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
        control(null, frame, callback);
    }

    @Override
    public void reset(ResetFrame frame, Callback callback)
    {
        control(null, frame, callback);
    }

    @Override
    public void close(int error, String reason, Callback callback)
    {
        byte[] payload = reason == null ? null : reason.getBytes(StandardCharsets.UTF_8);
        GoAwayFrame frame = new GoAwayFrame(lastStreamId.get(), error, payload);
        LOG.debug("Sending {}: {}", frame.getType(), reason);
        control(null, frame, callback);
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
        flusher.append(entry);
        flusher.iterate();
    }

    protected void disconnect()
    {
        LOG.debug("Disconnecting");
        endPoint.close();
    }

    protected IStream createLocalStream(HeadersFrame frame)
    {
        IStream stream = newStream(frame);
        int streamId = stream.getId();
        if (streams.putIfAbsent(streamId, stream) == null)
        {
            flowControl.onNewStream(stream);
            LOG.debug("Created local {}", stream);
            return stream;
        }
        else
        {
            return null;
        }
    }

    protected IStream createRemoteStream(HeadersFrame frame)
    {
        int streamId = frame.getStreamId();

        // SPEC: exceeding max concurrent streams is treated as stream error.
        while (true)
        {
            int currentStreams = streamCount.get();
            int maxStreams = maxStreamCount;
            if (maxStreams >= 0 && currentStreams >= maxStreams)
            {
                reset(new ResetFrame(streamId, ErrorCode.PROTOCOL_ERROR), disconnectCallback);
                return null;
            }
            if (streamCount.compareAndSet(currentStreams, currentStreams + 1))
                break;
        }

        IStream stream = newStream(frame);

        // SPEC: duplicate stream is treated as connection error.
        if (streams.putIfAbsent(streamId, stream) == null)
        {
            updateLastStreamId(streamId);
            flowControl.onNewStream(stream);
            LOG.debug("Created remote {}", stream);
            return stream;
        }
        else
        {
            close(ErrorCode.PROTOCOL_ERROR, "duplicate_stream", disconnectCallback);
            return null;
        }
    }

    protected IStream newStream(HeadersFrame frame)
    {
        return new HTTP2Stream(this, frame);
    }

    protected void removeStream(IStream stream, boolean local)
    {
        IStream removed = streams.remove(stream.getId());
        if (removed != null)
        {
            assert removed == stream;

            if (local)
                streamCount.decrementAndGet();

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
        private boolean closed;

        private Flusher(int maxGather)
        {
            this.maxGather = maxGather;
            this.active = new ArrayList<>(maxGather);
        }

        private void append(FlusherEntry entry)
        {
            boolean fail = false;
            synchronized (queue)
            {
                if (closed)
                    fail = true;
                else
                    queue.offer(entry);
            }
            if (fail)
                closed(entry);
        }

        private void prepend(FlusherEntry entry)
        {
            boolean fail = false;
            synchronized (queue)
            {
                if (closed)
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
                if (closed)
                    return Action.IDLE;

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
                // TODO: introduce a StreamResetException ?
                entry.failed(new IllegalStateException());
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
            endPoint.write(this, byteBuffers.toArray(new ByteBuffer[byteBuffers.size()]));
            return Action.SCHEDULED;
        }

        @Override
        public void succeeded()
        {
            lease.recycle();
            for (int i = 0; i < active.size(); ++i)
            {
                FlusherEntry entry = active.get(i);
                entry.succeeded();
            }
            active.clear();
            super.succeeded();
        }

        @Override
        public void failed(Throwable x)
        {
            LOG.debug(x);
            lease.recycle();
            for (int i = 0; i < active.size(); ++i)
            {
                FlusherEntry entry = active.get(i);
                entry.failed(x);
            }
            active.clear();
            super.failed(x);
        }

        @Override
        protected void completed()
        {
            throw new IllegalStateException();
        }

        public void close()
        {
            Queue<FlusherEntry> queued;
            synchronized (queue)
            {
                closed = true;
                queued = new ArrayDeque<>(queue);
            }

            while (true)
            {
                FlusherEntry item = queued.poll();
                if (item == null)
                    break;
                closed(item);
            }
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

        @Override
        public void succeeded()
        {
            callback.succeeded();
        }

        @Override
        public void failed(Throwable x)
        {
            callback.failed(x);
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
