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

package org.eclipse.jetty.spdy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.InterruptedByTimeoutException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.spdy.api.ByteBufferDataInfo;
import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.GoAwayInfo;
import org.eclipse.jetty.spdy.api.GoAwayResultInfo;
import org.eclipse.jetty.spdy.api.PingInfo;
import org.eclipse.jetty.spdy.api.PingResultInfo;
import org.eclipse.jetty.spdy.api.PushInfo;
import org.eclipse.jetty.spdy.api.RstInfo;
import org.eclipse.jetty.spdy.api.SPDYException;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.SessionFrameListener;
import org.eclipse.jetty.spdy.api.SessionStatus;
import org.eclipse.jetty.spdy.api.Settings;
import org.eclipse.jetty.spdy.api.SettingsInfo;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.StreamStatus;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.frames.ControlFrame;
import org.eclipse.jetty.spdy.frames.ControlFrameType;
import org.eclipse.jetty.spdy.frames.CredentialFrame;
import org.eclipse.jetty.spdy.frames.DataFrame;
import org.eclipse.jetty.spdy.frames.GoAwayFrame;
import org.eclipse.jetty.spdy.frames.HeadersFrame;
import org.eclipse.jetty.spdy.frames.PingFrame;
import org.eclipse.jetty.spdy.frames.RstStreamFrame;
import org.eclipse.jetty.spdy.frames.SettingsFrame;
import org.eclipse.jetty.spdy.frames.SynReplyFrame;
import org.eclipse.jetty.spdy.frames.SynStreamFrame;
import org.eclipse.jetty.spdy.frames.WindowUpdateFrame;
import org.eclipse.jetty.spdy.generator.Generator;
import org.eclipse.jetty.spdy.parser.Parser;
import org.eclipse.jetty.util.Atomics;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Scheduler;

public class StandardSession implements ISession, Parser.Listener, Dumpable
{
    private static final Logger LOG = Log.getLogger(Session.class);

    private final Flusher flusher;
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final ConcurrentMap<Integer, IStream> streams = new ConcurrentHashMap<>();
    private final ByteBufferPool bufferPool;
    private final Scheduler scheduler;
    private final short version;
    private final Controller controller;
    private final EndPoint endPoint;
    private final IdleListener idleListener;
    private final AtomicInteger streamIds;
    private final AtomicInteger pingIds;
    private final SessionFrameListener listener;
    private final Generator generator;
    private final AtomicBoolean goAwaySent = new AtomicBoolean();
    private final AtomicBoolean goAwayReceived = new AtomicBoolean();
    private final AtomicInteger lastStreamId = new AtomicInteger();
    private final AtomicInteger localStreamCount = new AtomicInteger(0);
    private final FlowControlStrategy flowControlStrategy;
    private volatile int maxConcurrentLocalStreams = -1;

    public StandardSession(short version, ByteBufferPool bufferPool, Scheduler scheduler,
                           Controller controller, EndPoint endPoint, IdleListener idleListener, int initialStreamId,
                           SessionFrameListener listener, Generator generator, FlowControlStrategy flowControlStrategy)
    {
        // TODO this should probably be an aggregate lifecycle
        this.version = version;
        this.bufferPool = bufferPool;
        this.scheduler = scheduler;
        this.controller = controller;
        this.endPoint = endPoint;
        this.idleListener = idleListener;
        this.streamIds = new AtomicInteger(initialStreamId);
        this.pingIds = new AtomicInteger(initialStreamId);
        this.listener = listener;
        this.generator = generator;
        this.flowControlStrategy = flowControlStrategy;
        this.flusher = new Flusher(controller);
    }

    @Override
    public short getVersion()
    {
        return version;
    }

    @Override
    public void addListener(Listener listener)
    {
        listeners.add(listener);
    }

    @Override
    public void removeListener(Listener listener)
    {
        listeners.remove(listener);
    }

    @Override
    public Stream syn(SynInfo synInfo, StreamFrameListener listener) throws ExecutionException, InterruptedException, TimeoutException
    {
        FuturePromise<Stream> result = new FuturePromise<>();
        syn(synInfo, listener, result);
        if (synInfo.getTimeout() > 0)
            return result.get(synInfo.getTimeout(), synInfo.getUnit());
        else
            return result.get();
    }

    @Override
    public void syn(SynInfo synInfo, StreamFrameListener listener, Promise<Stream> promise)
    {
        // Synchronization is necessary.
        // SPEC v3, 2.3.1 requires that the stream creation be monotonically crescent
        // so we cannot allow thread1 to create stream1 and thread2 create stream3 and
        // have stream3 hit the network before stream1, not only to comply with the spec
        // but also because the compression context for the headers would be wrong, as the
        // frame with a compression history will come before the first compressed frame.
        int associatedStreamId = 0;
        if (synInfo instanceof PushSynInfo)
            associatedStreamId = ((PushSynInfo)synInfo).getAssociatedStreamId();

        synchronized (this)
        {
            int streamId = streamIds.getAndAdd(2);
            // TODO: for SPDYv3 we need to support the "slot" argument
            SynStreamFrame synStream = new SynStreamFrame(version, synInfo.getFlags(), streamId, associatedStreamId, synInfo.getPriority(), (short)0, synInfo.getHeaders());
            IStream stream = createStream(synStream, listener, true, promise);
            if (stream == null)
                return;
            generateAndEnqueueControlFrame(stream, synStream, synInfo.getTimeout(), synInfo.getUnit(), stream);
        }
    }

    @Override
    public void rst(RstInfo rstInfo) throws InterruptedException, ExecutionException, TimeoutException
    {
        FutureCallback result = new FutureCallback();
        rst(rstInfo, result);
        if (rstInfo.getTimeout() > 0)
            result.get(rstInfo.getTimeout(), rstInfo.getUnit());
        else
            result.get();
    }

    @Override
    public void rst(RstInfo rstInfo, Callback callback)
    {
        // SPEC v3, 2.2.2
        if (goAwaySent.get())
        {
            complete(callback);
        }
        else
        {
            int streamId = rstInfo.getStreamId();
            IStream stream = streams.get(streamId);
            RstStreamFrame frame = new RstStreamFrame(version, streamId, rstInfo.getStreamStatus().getCode(version));
            control(stream, frame, rstInfo.getTimeout(), rstInfo.getUnit(), callback);
            if (stream != null)
            {
                stream.process(frame);
                flusher.removeFrameBytesFromQueue(stream);
                removeStream(stream);
            }
        }
    }

    @Override
    public void settings(SettingsInfo settingsInfo) throws ExecutionException, InterruptedException, TimeoutException
    {
        FutureCallback result = new FutureCallback();
        settings(settingsInfo, result);
        if (settingsInfo.getTimeout() > 0)
            result.get(settingsInfo.getTimeout(), settingsInfo.getUnit());
        else
            result.get();
    }

    @Override
    public void settings(SettingsInfo settingsInfo, Callback callback)
    {
        SettingsFrame frame = new SettingsFrame(version, settingsInfo.getFlags(), settingsInfo.getSettings());
        control(null, frame, settingsInfo.getTimeout(), settingsInfo.getUnit(), callback);
    }

    @Override
    public PingResultInfo ping(PingInfo pingInfo) throws ExecutionException, InterruptedException, TimeoutException
    {
        FuturePromise<PingResultInfo> result = new FuturePromise<>();
        ping(pingInfo, result);
        if (pingInfo.getTimeout() > 0)
            return result.get(pingInfo.getTimeout(), pingInfo.getUnit());
        else
            return result.get();
    }

    @Override
    public void ping(PingInfo pingInfo, Promise<PingResultInfo> promise)
    {
        int pingId = pingIds.getAndAdd(2);
        PingInfoCallback pingInfoCallback = new PingInfoCallback(pingId, promise);
        PingFrame frame = new PingFrame(version, pingId);
        control(null, frame, pingInfo.getTimeout(), pingInfo.getUnit(), pingInfoCallback);
    }

    @Override
    public void goAway(GoAwayInfo goAwayInfo) throws ExecutionException, InterruptedException, TimeoutException
    {
        goAway(goAwayInfo, SessionStatus.OK);
    }

    private void goAway(GoAwayInfo goAwayInfo, SessionStatus sessionStatus) throws ExecutionException, InterruptedException, TimeoutException
    {
        FutureCallback result = new FutureCallback();
        goAway(sessionStatus, goAwayInfo.getTimeout(), goAwayInfo.getUnit(), result);
        if (goAwayInfo.getTimeout() > 0)
            result.get(goAwayInfo.getTimeout(), goAwayInfo.getUnit());
        else
            result.get();
    }

    @Override
    public void goAway(GoAwayInfo goAwayInfo, Callback callback)
    {
        goAway(SessionStatus.OK, goAwayInfo.getTimeout(), goAwayInfo.getUnit(), callback);
    }

    private void goAway(SessionStatus sessionStatus, long timeout, TimeUnit unit, Callback callback)
    {
        if (goAwaySent.compareAndSet(false, true))
        {
            if (!goAwayReceived.get())
            {
                GoAwayFrame frame = new GoAwayFrame(version, lastStreamId.get(), sessionStatus.getCode());
                control(null, frame, timeout, unit, callback);
                return;
            }
        }
        complete(callback);
    }

    @Override
    public Set<Stream> getStreams()
    {
        Set<Stream> result = new HashSet<>();
        result.addAll(streams.values());
        return result;
    }

    @Override
    public IStream getStream(int streamId)
    {
        return streams.get(streamId);
    }

    @Override
    public Object getAttribute(String key)
    {
        return attributes.get(key);
    }

    @Override
    public void setAttribute(String key, Object value)
    {
        attributes.put(key, value);
    }

    @Override
    public Object removeAttribute(String key)
    {
        return attributes.remove(key);
    }

    @Override
    public InetSocketAddress getLocalAddress()
    {
        return endPoint.getLocalAddress();
    }

    @Override
    public InetSocketAddress getRemoteAddress()
    {
        return endPoint.getRemoteAddress();
    }

    @Override
    public void onControlFrame(ControlFrame frame)
    {
        notifyIdle(idleListener, false);
        try
        {
            LOG.debug("Processing {}", frame);

            if (goAwaySent.get())
            {
                LOG.debug("Skipped processing of {}", frame);
                return;
            }

            switch (frame.getType())
            {
                case SYN_STREAM:
                {
                    onSyn((SynStreamFrame)frame);
                    break;
                }
                case SYN_REPLY:
                {
                    onReply((SynReplyFrame)frame);
                    break;
                }
                case RST_STREAM:
                {
                    onRst((RstStreamFrame)frame);
                    break;
                }
                case SETTINGS:
                {
                    onSettings((SettingsFrame)frame);
                    break;
                }
                case NOOP:
                {
                    // Just ignore it
                    break;
                }
                case PING:
                {
                    onPing((PingFrame)frame);
                    break;
                }
                case GO_AWAY:
                {
                    onGoAway((GoAwayFrame)frame);
                    break;
                }
                case HEADERS:
                {
                    onHeaders((HeadersFrame)frame);
                    break;
                }
                case WINDOW_UPDATE:
                {
                    onWindowUpdate((WindowUpdateFrame)frame);
                    break;
                }
                case CREDENTIAL:
                {
                    onCredential((CredentialFrame)frame);
                    break;
                }
                default:
                {
                    throw new IllegalStateException();
                }
            }
        }
        finally
        {
            notifyIdle(idleListener, true);
        }
    }

    @Override
    public void onDataFrame(DataFrame frame, ByteBuffer data)
    {
        notifyIdle(idleListener, false);
        try
        {
            LOG.debug("Processing {}, {} data bytes", frame, data.remaining());

            if (goAwaySent.get())
            {
                LOG.debug("Skipped processing of {}", frame);
                return;
            }

            int streamId = frame.getStreamId();
            IStream stream = streams.get(streamId);
            if (stream == null)
            {
                RstInfo rstInfo = new RstInfo(streamId, StreamStatus.INVALID_STREAM);
                LOG.debug("Unknown stream {}", rstInfo);
                rst(rstInfo, Callback.Adapter.INSTANCE);
            }
            else
            {
                processData(stream, frame, data);
            }
        }
        finally
        {
            notifyIdle(idleListener, true);
        }
    }

    private void notifyIdle(IdleListener listener, boolean idle)
    {
        if (listener != null)
            listener.onIdle(idle);
    }

    private void processData(final IStream stream, DataFrame frame, ByteBuffer data)
    {
        ByteBufferDataInfo dataInfo = new ByteBufferDataInfo(data, frame.isClose())
        {
            @Override
            public void consume(int delta)
            {
                super.consume(delta);
                flowControlStrategy.onDataConsumed(StandardSession.this, stream, this, delta);
            }
        };
        flowControlStrategy.onDataReceived(this, stream, dataInfo);
        stream.process(dataInfo);
        if (stream.isClosed())
            removeStream(stream);
    }

    @Override
    public void onStreamException(StreamException x)
    {
        notifyOnFailure(listener, x); // TODO: notify StreamFrameListener if exists?
        rst(new RstInfo(x.getStreamId(), x.getStreamStatus()), Callback.Adapter.INSTANCE);
    }

    @Override
    public void onSessionException(SessionException x)
    {
        Throwable cause = x.getCause();
        notifyOnFailure(listener, cause == null ? x : cause);
        goAway(x.getSessionStatus(), 0, TimeUnit.SECONDS, Callback.Adapter.INSTANCE);
    }

    private void onSyn(final SynStreamFrame frame)
    {
        IStream stream = createStream(frame, null, false, new Promise.Adapter<Stream>()
        {
            @Override
            public void failed(Throwable x)
            {
                LOG.debug("Received: {} but creating new Stream failed: {}", frame, x.getMessage());
            }
        });
        if (stream != null)
            processSyn(listener, stream, frame);
    }

    private void processSyn(SessionFrameListener listener, IStream stream, SynStreamFrame frame)
    {
        stream.process(frame);
        // Update the last stream id before calling the application (which may send a GO_AWAY)
        updateLastStreamId(stream);
        StreamFrameListener streamListener;
        if (stream.isUnidirectional())
        {
            PushInfo pushInfo = new PushInfo(frame.getHeaders(), frame.isClose());
            streamListener = notifyOnPush(stream.getAssociatedStream().getStreamFrameListener(), stream, pushInfo);
        }
        else
        {
            SynInfo synInfo = new SynInfo(frame.getHeaders(), frame.isClose(), frame.getPriority());
            streamListener = notifyOnSyn(listener, stream, synInfo);
        }
        stream.setStreamFrameListener(streamListener);
        // The onSyn() listener may have sent a frame that closed the stream
        if (stream.isClosed())
            removeStream(stream);
    }

    private IStream createStream(SynStreamFrame frame, StreamFrameListener listener, boolean local, Promise<Stream> promise)
    {
        IStream associatedStream = streams.get(frame.getAssociatedStreamId());
        IStream stream = new StandardStream(frame.getStreamId(), frame.getPriority(), this, associatedStream,
                scheduler, promise);
        stream.setIdleTimeout(endPoint.getIdleTimeout());
        flowControlStrategy.onNewStream(this, stream);

        stream.updateCloseState(frame.isClose(), local);
        stream.setStreamFrameListener(listener);

        if (stream.isUnidirectional())
        {
            // Unidirectional streams are implicitly half closed
            stream.updateCloseState(true, !local);
            if (!stream.isClosed())
                stream.getAssociatedStream().associate(stream);
        }

        int streamId = stream.getId();

        if (local)
        {
            while (true)
            {
                int oldStreamCountValue = localStreamCount.get();
                int maxConcurrentStreams = maxConcurrentLocalStreams;
                if (maxConcurrentStreams > -1 && oldStreamCountValue >= maxConcurrentStreams)
                {
                    String message = String.format("Max concurrent local streams (%d) exceeded.",
                            maxConcurrentStreams);
                    LOG.debug(message);
                    promise.failed(new SPDYException(message));
                    return null;
                }
                if (localStreamCount.compareAndSet(oldStreamCountValue, oldStreamCountValue + 1))
                    break;
            }
        }

        if (streams.putIfAbsent(streamId, stream) != null)
        {
            String message = "Duplicate stream id " + streamId;
            IllegalStateException duplicateIdException = new IllegalStateException(message);
            promise.failed(duplicateIdException);
            if (local)
            {
                localStreamCount.decrementAndGet();
                throw duplicateIdException;
            }
            RstInfo rstInfo = new RstInfo(streamId, StreamStatus.PROTOCOL_ERROR);
            LOG.debug("Duplicate stream, {}", rstInfo);
            rst(rstInfo, Callback.Adapter.INSTANCE); // We don't care (too much) if the reset fails.
            return null;
        }
        else
        {
            LOG.debug("Created {}", stream);
            notifyStreamCreated(stream);
            return stream;
        }
    }

    private void notifyStreamCreated(IStream stream)
    {
        for (Listener listener : listeners)
        {
            if (listener instanceof StreamListener)
            {
                try
                {
                    ((StreamListener)listener).onStreamCreated(stream);
                }
                catch (Exception x)
                {
                    LOG.info("Exception while notifying listener " + listener, x);
                }
                catch (Error x)
                {
                    LOG.info("Exception while notifying listener " + listener, x);
                    throw x;
                }
            }
        }
    }

    private void removeStream(IStream stream)
    {
        if (stream.isUnidirectional())
            stream.getAssociatedStream().disassociate(stream);

        IStream removed = streams.remove(stream.getId());
        if (removed != null)
        {
            assert removed == stream;

            if (streamIds.get() % 2 == stream.getId() % 2)
                localStreamCount.decrementAndGet();

            LOG.debug("Removed {}", stream);
            notifyStreamClosed(stream);
        }
    }

    private void notifyStreamClosed(IStream stream)
    {
        for (Listener listener : listeners)
        {
            if (listener instanceof StreamListener)
            {
                try
                {
                    ((StreamListener)listener).onStreamClosed(stream);
                }
                catch (Exception x)
                {
                    LOG.info("Exception while notifying listener " + listener, x);
                }
                catch (Error x)
                {
                    LOG.info("Exception while notifying listener " + listener, x);
                    throw x;
                }
            }
        }
    }

    private void onReply(SynReplyFrame frame)
    {
        int streamId = frame.getStreamId();
        IStream stream = streams.get(streamId);
        if (stream == null)
        {
            RstInfo rstInfo = new RstInfo(streamId, StreamStatus.INVALID_STREAM);
            LOG.debug("Unknown stream {}", rstInfo);
            rst(rstInfo, Callback.Adapter.INSTANCE);
        }
        else
        {
            processReply(stream, frame);
        }
    }

    private void processReply(IStream stream, SynReplyFrame frame)
    {
        stream.process(frame);
        if (stream.isClosed())
            removeStream(stream);
    }

    private void onRst(RstStreamFrame frame)
    {
        IStream stream = streams.get(frame.getStreamId());

        if (stream != null)
            stream.process(frame);

        RstInfo rstInfo = new RstInfo(frame.getStreamId(), StreamStatus.from(frame.getVersion(), frame.getStatusCode()));
        notifyOnRst(listener, rstInfo);

        if (stream != null)
            removeStream(stream);
    }

    private void onSettings(SettingsFrame frame)
    {
        Settings.Setting windowSizeSetting = frame.getSettings().get(Settings.ID.INITIAL_WINDOW_SIZE);
        if (windowSizeSetting != null)
        {
            int windowSize = windowSizeSetting.value();
            setWindowSize(windowSize);
            LOG.debug("Updated session window size to {}", windowSize);
        }
        Settings.Setting maxConcurrentStreamsSetting = frame.getSettings().get(Settings.ID.MAX_CONCURRENT_STREAMS);
        if (maxConcurrentStreamsSetting != null)
        {
            int maxConcurrentStreamsValue = maxConcurrentStreamsSetting.value();
            maxConcurrentLocalStreams = maxConcurrentStreamsValue;
            LOG.debug("Updated session maxConcurrentLocalStreams to {}", maxConcurrentStreamsValue);
        }
        SettingsInfo settingsInfo = new SettingsInfo(frame.getSettings(), frame.isClearPersisted());
        notifyOnSettings(listener, settingsInfo);
    }

    private void onPing(PingFrame frame)
    {
        int pingId = frame.getPingId();
        if (pingId % 2 == pingIds.get() % 2)
        {
            PingResultInfo pingResultInfo = new PingResultInfo(frame.getPingId());
            notifyOnPing(listener, pingResultInfo);
        }
        else
        {
            control(null, frame, 0, TimeUnit.MILLISECONDS, Callback.Adapter.INSTANCE);
        }
    }

    private void onGoAway(GoAwayFrame frame)
    {
        if (goAwayReceived.compareAndSet(false, true))
        {
            GoAwayResultInfo goAwayResultInfo = new GoAwayResultInfo(frame.getLastStreamId(), SessionStatus.from(frame.getStatusCode()));
            notifyOnGoAway(listener, goAwayResultInfo);
            // SPDY does not require to send back a response to a GO_AWAY.
            // We notified the application of the last good stream id and
            // tried our best to flush remaining data.
        }
    }

    private void onHeaders(HeadersFrame frame)
    {
        int streamId = frame.getStreamId();
        IStream stream = streams.get(streamId);
        if (stream == null)
        {
            RstInfo rstInfo = new RstInfo(streamId, StreamStatus.INVALID_STREAM);
            LOG.debug("Unknown stream, {}", rstInfo);
            rst(rstInfo, Callback.Adapter.INSTANCE);
        }
        else
        {
            processHeaders(stream, frame);
        }
    }

    private void processHeaders(IStream stream, HeadersFrame frame)
    {
        stream.process(frame);
        if (stream.isClosed())
            removeStream(stream);
    }

    private void onWindowUpdate(WindowUpdateFrame frame)
    {
        int streamId = frame.getStreamId();
        IStream stream = streams.get(streamId);
        flowControlStrategy.onWindowUpdate(this, stream, frame.getWindowDelta());
        flusher.flush();
    }

    private void onCredential(CredentialFrame frame)
    {
        LOG.warn("{} frame not yet supported", frame.getType());
    }

    protected void close()
    {
        // Check for null to support tests
        if (controller != null)
            controller.close(false);
    }

    private void notifyOnFailure(SessionFrameListener listener, Throwable x)
    {
        try
        {
            if (listener != null)
            {
                LOG.debug("Invoking callback with {} on listener {}", x, listener);
                listener.onFailure(this, x);
            }
        }
        catch (Exception xx)
        {
            LOG.info("Exception while notifying listener " + listener, xx);
        }
        catch (Error xx)
        {
            LOG.info("Exception while notifying listener " + listener, xx);
            throw xx;
        }
    }

    private StreamFrameListener notifyOnPush(StreamFrameListener listener, Stream stream, PushInfo pushInfo)
    {
        try
        {
            if (listener == null)
                return null;
            LOG.debug("Invoking callback with {} on listener {}", pushInfo, listener);
            return listener.onPush(stream, pushInfo);
        }
        catch (Exception x)
        {
            LOG.info("Exception while notifying listener " + listener, x);
            return null;
        }
        catch (Error x)
        {
            LOG.info("Exception while notifying listener " + listener, x);
            throw x;
        }
    }

    private StreamFrameListener notifyOnSyn(SessionFrameListener listener, Stream stream, SynInfo synInfo)
    {
        try
        {
            if (listener == null)
                return null;
            LOG.debug("Invoking callback with {} on listener {}", synInfo, listener);
            return listener.onSyn(stream, synInfo);
        }
        catch (Exception x)
        {
            LOG.info("Exception while notifying listener " + listener, x);
            return null;
        }
        catch (Error x)
        {
            LOG.info("Exception while notifying listener " + listener, x);
            throw x;
        }
    }

    private void notifyOnRst(SessionFrameListener listener, RstInfo rstInfo)
    {
        try
        {
            if (listener != null)
            {
                LOG.debug("Invoking callback with {} on listener {}", rstInfo, listener);
                listener.onRst(this, rstInfo);
            }
        }
        catch (Exception x)
        {
            LOG.info("Exception while notifying listener " + listener, x);
        }
        catch (Error x)
        {
            LOG.info("Exception while notifying listener " + listener, x);
            throw x;
        }
    }

    private void notifyOnSettings(SessionFrameListener listener, SettingsInfo settingsInfo)
    {
        try
        {
            if (listener != null)
            {
                LOG.debug("Invoking callback with {} on listener {}", settingsInfo, listener);
                listener.onSettings(this, settingsInfo);
            }
        }
        catch (Exception x)
        {
            LOG.info("Exception while notifying listener " + listener, x);
        }
        catch (Error x)
        {
            LOG.info("Exception while notifying listener " + listener, x);
            throw x;
        }
    }

    private void notifyOnPing(SessionFrameListener listener, PingResultInfo pingResultInfo)
    {
        try
        {
            if (listener != null)
            {
                LOG.debug("Invoking callback with {} on listener {}", pingResultInfo, listener);
                listener.onPing(this, pingResultInfo);
            }
        }
        catch (Exception x)
        {
            LOG.info("Exception while notifying listener " + listener, x);
        }
        catch (Error x)
        {
            LOG.info("Exception while notifying listener " + listener, x);
            throw x;
        }
    }

    private void notifyOnGoAway(SessionFrameListener listener, GoAwayResultInfo goAwayResultInfo)
    {
        try
        {
            if (listener != null)
            {
                LOG.debug("Invoking callback with {} on listener {}", goAwayResultInfo, listener);
                listener.onGoAway(this, goAwayResultInfo);
            }
        }
        catch (Exception x)
        {
            LOG.info("Exception while notifying listener " + listener, x);
        }
        catch (Error x)
        {
            LOG.info("Exception while notifying listener " + listener, x);
            throw x;
        }
    }

    @Override
    public void control(IStream stream, ControlFrame frame, long timeout, TimeUnit unit, Callback callback)
    {
        generateAndEnqueueControlFrame(stream, frame, timeout, unit, callback);
    }

    private void generateAndEnqueueControlFrame(IStream stream, ControlFrame frame, long timeout, TimeUnit unit, Callback callback)
    {
        try
        {
            // Synchronization is necessary, since we may have concurrent replies
            // and those needs to be generated and enqueued atomically in order
            // to maintain a correct compression context
            ControlFrameBytes frameBytes;
            Throwable throwable;
            synchronized (this)
            {
                ByteBuffer buffer = generator.control(frame);
                LOG.debug("Queuing {} on {}", frame, stream);
                frameBytes = new ControlFrameBytes(stream, callback, frame, buffer);
                if (timeout > 0)
                    frameBytes.task = scheduler.schedule(frameBytes, timeout, unit);

                // Special handling for PING frames, they must be sent as soon as possible
                if (ControlFrameType.PING == frame.getType())
                    throwable = flusher.prepend(frameBytes);
                else
                    throwable = flusher.append(frameBytes);
            }
            // Flush MUST be done outside synchronized blocks
            flush(frameBytes, throwable);
        }
        catch (Exception x)
        {
            notifyCallbackFailed(callback, x);
        }
    }

    private void updateLastStreamId(IStream stream)
    {
        int streamId = stream.getId();
        if (streamId % 2 != streamIds.get() % 2)
            Atomics.updateMax(lastStreamId, streamId);
    }

    @Override
    public void data(IStream stream, DataInfo dataInfo, long timeout, TimeUnit unit, Callback callback)
    {
        LOG.debug("Queuing {} on {}", dataInfo, stream);
        DataFrameBytes frameBytes = new DataFrameBytes(stream, callback, dataInfo);
        if (timeout > 0)
            frameBytes.task = scheduler.schedule(frameBytes, timeout, unit);
        flush(frameBytes, flusher.append(frameBytes));
    }

    @Override
    public void shutdown()
    {
        CloseFrameBytes frameBytes = new CloseFrameBytes();
        flush(frameBytes, flusher.append(frameBytes));
    }

    private void flush(FrameBytes frameBytes, Throwable throwable)
    {
        if (throwable != null)
            frameBytes.failed(throwable);
        else
            flusher.flush();
    }

    private void complete(final Callback callback)
    {
        try
        {
            if (callback != null)
                callback.succeeded();
        }
        catch (Throwable x)
        {
            LOG.info("Exception while notifying callback " + callback, x);
        }
    }

    private void notifyCallbackFailed(Callback callback, Throwable failure)
    {
        try
        {
            if (callback != null)
                callback.failed(failure);
        }
        catch (Throwable x)
        {
            LOG.info("Exception while notifying callback " + callback, x);
        }
    }

    public int getWindowSize()
    {
        return flowControlStrategy.getWindowSize(this);
    }

    public void setWindowSize(int initialWindowSize)
    {
        flowControlStrategy.setWindowSize(this, initialWindowSize);
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{v%d,queueSize=%d,windowSize=%d,streams=%d}", getClass().getSimpleName(),
                hashCode(), version, flusher.getQueueSize(), getWindowSize(), streams.size());
    }

    @Override
    public String dump()
    {
        return ContainerLifeCycle.dump(this);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        ContainerLifeCycle.dumpObject(out, this);
        ContainerLifeCycle.dump(out, indent, Collections.singletonList(controller), streams.values());
    }

    public interface FrameBytes extends Comparable<FrameBytes>, Callback
    {
        public IStream getStream();

        public abstract ByteBuffer getByteBuffer();
    }

    abstract class AbstractFrameBytes implements FrameBytes, Runnable
    {
        private final IStream stream;
        private final Callback callback;
        protected volatile Scheduler.Task task;

        protected AbstractFrameBytes(IStream stream, Callback callback)
        {
            this.stream = stream;
            this.callback = Objects.requireNonNull(callback);
        }

        @Override
        public IStream getStream()
        {
            return stream;
        }

        @Override
        public int compareTo(FrameBytes that)
        {
            // FrameBytes may have or not have a related stream (for example, PING do not have a related stream)
            // FrameBytes without related streams have higher priority
            IStream thisStream = getStream();
            IStream thatStream = that.getStream();
            if (thisStream == null)
                return thatStream == null ? 0 : -1;
            if (thatStream == null)
                return 1;
            // If this.stream.priority > that.stream.priority => this.stream has less priority than that.stream
            return thatStream.getPriority() - thisStream.getPriority();
        }

        private void cancelTask()
        {
            Scheduler.Task task = this.task;
            if (task != null)
                task.cancel();
        }

        @Override
        public void run()
        {
            close();
            failed(new InterruptedByTimeoutException());
        }

        @Override
        public void succeeded()
        {
            cancelTask();
            StandardSession.this.complete(callback);
        }

        @Override
        public void failed(Throwable x)
        {
            cancelTask();
            notifyCallbackFailed(callback, x);
        }
    }

    protected class ControlFrameBytes extends AbstractFrameBytes
    {
        private final ControlFrame frame;
        private final ByteBuffer buffer;

        private ControlFrameBytes(IStream stream, Callback callback, ControlFrame frame, ByteBuffer buffer)
        {
            super(stream, callback);
            this.frame = frame;
            this.buffer = buffer;
        }

        @Override
        public ByteBuffer getByteBuffer()
        {
            return buffer;
        }

        @Override
        public void succeeded()
        {
            bufferPool.release(buffer);

            super.succeeded();

            if (frame.getType() == ControlFrameType.GO_AWAY)
            {
                // After sending a GO_AWAY we need to hard close the connection.
                // Recipients will know the last good stream id and act accordingly.
                close();
            }
            IStream stream = getStream();
            if (stream != null && stream.isClosed())
                removeStream(stream);
        }

        @Override
        public String toString()
        {
            return frame.toString();
        }
    }

    protected class DataFrameBytes extends AbstractFrameBytes
    {
        private final DataInfo dataInfo;
        private int size;
        private volatile ByteBuffer buffer;

        private DataFrameBytes(IStream stream, Callback handler, DataInfo dataInfo)
        {
            super(stream, handler);
            this.dataInfo = dataInfo;
        }

        @Override
        public ByteBuffer getByteBuffer()
        {
            try
            {
                IStream stream = getStream();
                int windowSize = stream.getWindowSize();

                // TODO: optimization
                // Right now, we use the windowSize to chunk big buffers.
                // However, if the window size is large, we may congest the
                // connection, or favor one stream that does a big download,
                // starving the other streams.
                // Also, SPDY DATA frames have a maximum of 16 MiB size, which
                // is not enforced here.
                // We should have a configurable "preferredDataFrameSize"
                // (or even better autotuning) that will allow to send chunks
                // that will not starve other streams and small enough to
                // not congest the connection, while avoiding to send too many
                // TCP packets.
                // See also comment in class Flusher.

                size = dataInfo.available();
                if (size > windowSize)
                    size = windowSize;

                buffer = generator.data(stream.getId(), size, dataInfo);
                return buffer;
            }
            catch (Throwable x)
            {
                failed(x);
                return null;
            }
        }

        @Override
        public void succeeded()
        {
            bufferPool.release(buffer);
            IStream stream = getStream();
            dataInfo.consume(size);
            flowControlStrategy.updateWindow(StandardSession.this, stream, -size);
            if (dataInfo.available() > 0)
            {
                // We have written a frame out of this DataInfo, but there is more to write.
                // We need to keep the correct ordering of frames, to avoid that another
                // DataInfo for the same stream is written before this one is finished.
                flush(this, flusher.prepend(this));
            }
            else
            {
                super.succeeded();
                stream.updateCloseState(dataInfo.isClose(), true);
                if (stream.isClosed())
                    removeStream(stream);
            }
        }

        @Override
        public String toString()
        {
            return String.format("DATA bytes @%x available=%d consumed=%d on %s", dataInfo.hashCode(), dataInfo.available(), dataInfo.consumed(), getStream());
        }
    }

    protected class CloseFrameBytes extends AbstractFrameBytes
    {
        private CloseFrameBytes()
        {
            super(null, Callback.Adapter.INSTANCE);
        }

        @Override
        public ByteBuffer getByteBuffer()
        {
            return BufferUtil.EMPTY_BUFFER;
        }

        @Override
        public void succeeded()
        {
            super.succeeded();
            close();
        }
    }

    private static class PingInfoCallback extends PingResultInfo implements Callback
    {
        private final Promise<PingResultInfo> promise;

        public PingInfoCallback(int pingId, Promise<PingResultInfo> promise)
        {
            super(pingId);
            this.promise = promise;
        }

        @Override
        public void succeeded()
        {
            if (promise != null)
                promise.succeeded(this);
        }

        @Override
        public void failed(Throwable x)
        {
            if (promise != null)
                promise.failed(x);
        }
    }
}
