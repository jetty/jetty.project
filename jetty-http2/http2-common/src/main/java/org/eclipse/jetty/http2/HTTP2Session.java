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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.ArrayQueue;
import org.eclipse.jetty.util.Atomics;
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
    private final Flusher flusher = new Flusher();
    private final EndPoint endPoint;
    private final Generator generator;
    private final Listener listener;
    private final FlowControl flowControl;
    private final int initialWindowSize;
    private volatile int maxStreamCount;

    public HTTP2Session(EndPoint endPoint, Generator generator, Listener listener, FlowControl flowControl, int initialWindowSize, int initialStreamId)
    {
        this.endPoint = endPoint;
        this.generator = generator;
        this.listener = listener;
        this.flowControl = flowControl;
        this.initialWindowSize = initialWindowSize;
        this.maxStreamCount = -1;
        this.streamIds.set(initialStreamId);
    }

    public Generator getGenerator()
    {
        return generator;
    }

    public int getInitialWindowSize()
    {
        return initialWindowSize;
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
    public boolean onData(DataFrame frame)
    {
        int streamId = frame.getStreamId();
        IStream stream = getStream(streamId);
        if (stream != null)
        {
            stream.updateClose(frame.isEndStream(), false);
            return stream.process(frame);
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
            setWindowSize(windowSize);
            LOG.debug("Updated window size to {}", windowSize);
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
        return false;
    }

    @Override
    public boolean onWindowUpdate(WindowUpdateFrame frame)
    {
        return false;
    }

    @Override
    public void onConnectionFailure(int error, String reason)
    {
        // TODO
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
            flusher.offer(generator.generate(frame, new PromiseCallback<>(promise, stream)));
        }
        // Iterate outside the synchronized block.
        flusher.iterate();
    }

    @Override
    public void settings(SettingsFrame frame, Callback callback)
    {
        frame(frame, callback);
    }

    @Override
    public void ping(PingFrame frame, Callback callback)
    {
        frame(frame, callback);
    }

    @Override
    public void reset(ResetFrame frame, Callback callback)
    {
        frame(frame, callback);
    }

    @Override
    public void close(int error, String reason, Callback callback)
    {
        byte[] payload = reason == null ? null : reason.getBytes(StandardCharsets.UTF_8);
        GoAwayFrame frame = new GoAwayFrame(lastStreamId.get(), error, payload);
        frame(frame, callback);
    }

    @Override
    public void frame(Frame frame, Callback callback)
    {
        Generator.LeaseCallback lease = generator.generate(frame, callback);
        flusher.flush(lease);
    }

    protected void disconnect()
    {
        endPoint.close();
    }

    protected IStream createLocalStream(HeadersFrame frame)
    {
        IStream stream = newStream(frame);
        int streamId = stream.getId();
        updateLastStreamId(streamId);
        if (streams.putIfAbsent(streamId, stream) == null)
        {
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

    private void updateLastStreamId(int streamId)
    {
        Atomics.updateMax(lastStreamId, streamId);
    }

    public void setWindowSize(int initialWindowSize)
    {
        flowControl.setWindowSize(this, initialWindowSize);
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

    private class Flusher extends IteratingCallback
    {
        private final ArrayQueue<Generator.LeaseCallback> queue = new ArrayQueue<>(ArrayQueue.DEFAULT_CAPACITY, ArrayQueue.DEFAULT_GROWTH);
        private Generator.LeaseCallback active;

        private void offer(Generator.LeaseCallback lease)
        {
            synchronized (queue)
            {
                queue.offer(lease);
            }
        }

        private void flush(Generator.LeaseCallback lease)
        {
            offer(lease);
            iterate();
        }

        @Override
        protected Action process() throws Exception
        {
            synchronized (queue)
            {
                active = queue.poll();
            }
            if (active == null)
            {
                return Action.IDLE;
            }

            List<ByteBuffer> byteBuffers = active.getByteBuffers();
            endPoint.write(this, byteBuffers.toArray(new ByteBuffer[byteBuffers.size()]));
            return Action.SCHEDULED;
        }

        @Override
        public void succeeded()
        {
            active.succeeded();
            super.succeeded();
        }

        @Override
        public void failed(Throwable x)
        {
            active.failed(x);
            super.failed(x);
        }

        @Override
        protected void completed()
        {
        }
    }

    public class PromiseCallback<C> implements Callback
    {
        private final Promise<C> promise;
        private final C value;

        public PromiseCallback(Promise<C> promise, C value)
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
