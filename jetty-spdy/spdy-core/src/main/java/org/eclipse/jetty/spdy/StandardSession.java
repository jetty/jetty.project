//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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
import java.nio.ByteBuffer;
import java.nio.channels.InterruptedByTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.spdy.api.ByteBufferDataInfo;
import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.GoAwayInfo;
import org.eclipse.jetty.spdy.api.Handler;
import org.eclipse.jetty.spdy.api.PingInfo;
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
import org.eclipse.jetty.util.component.AggregateLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class StandardSession implements ISession, Parser.Listener, Handler<StandardSession.FrameBytes>, Dumpable
{
    private static final Logger logger = Log.getLogger(Session.class);
    private static final ThreadLocal<Integer> handlerInvocations = new ThreadLocal<Integer>()
    {
        @Override
        protected Integer initialValue()
        {
            return 0;
        }
    };

    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final ConcurrentMap<Integer, IStream> streams = new ConcurrentHashMap<>();
    private final LinkedList<FrameBytes> queue = new LinkedList<>();
    private final ByteBufferPool bufferPool;
    private final Executor threadPool;
    private final ScheduledExecutorService scheduler;
    private final short version;
    private final Controller<FrameBytes> controller;
    private final IdleListener idleListener;
    private final AtomicInteger streamIds;
    private final AtomicInteger pingIds;
    private final SessionFrameListener listener;
    private final Generator generator;
    private final AtomicBoolean goAwaySent = new AtomicBoolean();
    private final AtomicBoolean goAwayReceived = new AtomicBoolean();
    private final AtomicInteger lastStreamId = new AtomicInteger();
    private final FlowControlStrategy flowControlStrategy;
    private boolean flushing;
    private Throwable failure;

    public StandardSession(short version, ByteBufferPool bufferPool, Executor threadPool, ScheduledExecutorService scheduler,
            Controller<FrameBytes> controller, IdleListener idleListener, int initialStreamId, SessionFrameListener listener,
            Generator generator, FlowControlStrategy flowControlStrategy)
    {
        this.version = version;
        this.bufferPool = bufferPool;
        this.threadPool = threadPool;
        this.scheduler = scheduler;
        this.controller = controller;
        this.idleListener = idleListener;
        this.streamIds = new AtomicInteger(initialStreamId);
        this.pingIds = new AtomicInteger(initialStreamId);
        this.listener = listener;
        this.generator = generator;
        this.flowControlStrategy = flowControlStrategy;
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
    public Future<Stream> syn(SynInfo synInfo, StreamFrameListener listener)
    {
        Promise<Stream> result = new Promise<>();
        syn(synInfo,listener,0,TimeUnit.MILLISECONDS,result);
        return result;
    }

    @Override
    public void syn(SynInfo synInfo, StreamFrameListener listener, long timeout, TimeUnit unit, Handler<Stream> handler)
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
            IStream stream = createStream(synStream, listener, true);
            generateAndEnqueueControlFrame(stream, synStream, timeout, unit, handler, stream);
        }
        flush();
    }

    @Override
    public Future<Void> rst(RstInfo rstInfo)
    {
        Promise<Void> result = new Promise<>();
        rst(rstInfo,0,TimeUnit.MILLISECONDS,result);
        return result;
    }

    @Override
    public void rst(RstInfo rstInfo, long timeout, TimeUnit unit, Handler<Void> handler)
    {
        // SPEC v3, 2.2.2
        if (goAwaySent.get())
        {
            complete(handler,null);
        }
        else
        {
            int streamId = rstInfo.getStreamId();
            IStream stream = streams.get(streamId);
            RstStreamFrame frame = new RstStreamFrame(version,streamId,rstInfo.getStreamStatus().getCode(version));
            control(stream,frame,timeout,unit,handler,null);
            if (stream != null)
            {
                stream.process(frame);
                removeStream(stream);
            }
        }
    }

    @Override
    public Future<Void> settings(SettingsInfo settingsInfo)
    {
        Promise<Void> result = new Promise<>();
        settings(settingsInfo,0,TimeUnit.MILLISECONDS,result);
        return result;
    }

    @Override
    public void settings(SettingsInfo settingsInfo, long timeout, TimeUnit unit, Handler<Void> handler)
    {
        SettingsFrame frame = new SettingsFrame(version,settingsInfo.getFlags(),settingsInfo.getSettings());
        control(null, frame, timeout, unit, handler, null);
    }

    @Override
    public Future<PingInfo> ping()
    {
        Promise<PingInfo> result = new Promise<>();
        ping(0, TimeUnit.MILLISECONDS, result);
        return result;
    }

    @Override
    public void ping(long timeout, TimeUnit unit, Handler<PingInfo> handler)
    {
        int pingId = pingIds.getAndAdd(2);
        PingInfo pingInfo = new PingInfo(pingId);
        PingFrame frame = new PingFrame(version,pingId);
        control(null,frame,timeout,unit,handler,pingInfo);
    }

    @Override
    public Future<Void> goAway()
    {
        return goAway(SessionStatus.OK);
    }

    private Future<Void> goAway(SessionStatus sessionStatus)
    {
        Promise<Void> result = new Promise<>();
        goAway(sessionStatus, 0, TimeUnit.MILLISECONDS, result);
        return result;
    }

    @Override
    public void goAway(long timeout, TimeUnit unit, Handler<Void> handler)
    {
        goAway(SessionStatus.OK, timeout, unit, handler);
    }

    private void goAway(SessionStatus sessionStatus, long timeout, TimeUnit unit, Handler<Void> handler)
    {
        if (goAwaySent.compareAndSet(false,true))
        {
            if (!goAwayReceived.get())
            {
                GoAwayFrame frame = new GoAwayFrame(version,lastStreamId.get(),sessionStatus.getCode());
                control(null,frame,timeout,unit,handler,null);
                return;
            }
        }
        complete(handler, null);
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
    public void onControlFrame(ControlFrame frame)
    {
        notifyIdle(idleListener, false);
        try
        {
            logger.debug("Processing {}", frame);

            if (goAwaySent.get())
            {
                logger.debug("Skipped processing of {}", frame);
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
            logger.debug("Processing {}, {} data bytes", frame, data.remaining());

            if (goAwaySent.get())
            {
                logger.debug("Skipped processing of {}", frame);
                return;
            }

            int streamId = frame.getStreamId();
            IStream stream = streams.get(streamId);
            if (stream == null)
            {
                RstInfo rstInfo = new RstInfo(streamId, StreamStatus.INVALID_STREAM);
                logger.debug("Unknown stream {}", rstInfo);
                rst(rstInfo);
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
        ByteBufferDataInfo dataInfo = new ByteBufferDataInfo(data, frame.isClose(), frame.isCompress())
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
        notifyOnException(listener, x);
        rst(new RstInfo(x.getStreamId(),x.getStreamStatus()));
    }

    @Override
    public void onSessionException(SessionException x)
    {
        Throwable cause = x.getCause();
        notifyOnException(listener,cause == null?x:cause);
        goAway(x.getSessionStatus());
    }

    private void onSyn(SynStreamFrame frame)
    {
        IStream stream = createStream(frame, null, false);
        if (stream != null)
            processSyn(listener, stream, frame);
    }

    private void processSyn(SessionFrameListener listener, IStream stream, SynStreamFrame frame)
    {
        stream.process(frame);
        // Update the last stream id before calling the application (which may send a GO_AWAY)
        updateLastStreamId(stream);
        SynInfo synInfo = new SynInfo(frame.getHeaders(),frame.isClose(),frame.getPriority());
        StreamFrameListener streamListener = notifyOnSyn(listener,stream,synInfo);
        stream.setStreamFrameListener(streamListener);
        flush();
        // The onSyn() listener may have sent a frame that closed the stream
        if (stream.isClosed())
            removeStream(stream);
    }

    private IStream createStream(SynStreamFrame frame, StreamFrameListener listener, boolean local)
    {
        IStream stream = newStream(frame);
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
        if (streams.putIfAbsent(streamId, stream) != null)
        {
            if (local)
                throw new IllegalStateException("Duplicate stream id " + streamId);
            RstInfo rstInfo = new RstInfo(streamId, StreamStatus.PROTOCOL_ERROR);
            logger.debug("Duplicate stream, {}", rstInfo);
            rst(rstInfo);
            return null;
        }
        else
        {
            logger.debug("Created {}", stream);
            if (local)
                notifyStreamCreated(stream);
            return stream;
        }
    }

    private IStream newStream(SynStreamFrame frame)
    {
        IStream associatedStream = streams.get(frame.getAssociatedStreamId());
        IStream stream = new StandardStream(frame.getStreamId(), frame.getPriority(), this, associatedStream);
        flowControlStrategy.onNewStream(this, stream);
        return stream;
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
                    logger.info("Exception while notifying listener " + listener, x);
                }
                catch (Error x)
                {
                    logger.info("Exception while notifying listener " + listener, x);
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
            assert removed == stream;

        logger.debug("Removed {}", stream);
        notifyStreamClosed(stream);
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
                    logger.info("Exception while notifying listener " + listener, x);
                }
                catch (Error x)
                {
                    logger.info("Exception while notifying listener " + listener, x);
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
            RstInfo rstInfo = new RstInfo(streamId,StreamStatus.INVALID_STREAM);
            logger.debug("Unknown stream {}",rstInfo);
            rst(rstInfo);
        }
        else
        {
            processReply(stream,frame);
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

        RstInfo rstInfo = new RstInfo(frame.getStreamId(),StreamStatus.from(frame.getVersion(),frame.getStatusCode()));
        notifyOnRst(listener, rstInfo);
        flush();

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
            logger.debug("Updated session window size to {}", windowSize);
        }
        SettingsInfo settingsInfo = new SettingsInfo(frame.getSettings(), frame.isClearPersisted());
        notifyOnSettings(listener, settingsInfo);
        flush();
    }

    private void onPing(PingFrame frame)
    {
        int pingId = frame.getPingId();
        if (pingId % 2 == pingIds.get() % 2)
        {
            PingInfo pingInfo = new PingInfo(frame.getPingId());
            notifyOnPing(listener, pingInfo);
            flush();
        }
        else
        {
            control(null, frame, 0, TimeUnit.MILLISECONDS, null, null);
        }
    }

    private void onGoAway(GoAwayFrame frame)
    {
        if (goAwayReceived.compareAndSet(false, true))
        {
            GoAwayInfo goAwayInfo = new GoAwayInfo(frame.getLastStreamId(),SessionStatus.from(frame.getStatusCode()));
            notifyOnGoAway(listener,goAwayInfo);
            flush();
            // SPDY does not require to send back a response to a GO_AWAY.
            // We notified the application of the last good stream id,
            // tried our best to flush remaining data, and close.
            close();
        }
    }

    private void onHeaders(HeadersFrame frame)
    {
        int streamId = frame.getStreamId();
        IStream stream = streams.get(streamId);
        if (stream == null)
        {
            RstInfo rstInfo = new RstInfo(streamId,StreamStatus.INVALID_STREAM);
            logger.debug("Unknown stream, {}",rstInfo);
            rst(rstInfo);
        }
        else
        {
            processHeaders(stream,frame);
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
        flush();
    }

    private void onCredential(CredentialFrame frame)
    {
        logger.warn("{} frame not yet supported", ControlFrameType.CREDENTIAL);
        flush();
    }

    protected void close()
    {
        // Check for null to support tests
        if (controller != null)
            controller.close(false);
    }

    private void notifyOnException(SessionFrameListener listener, Throwable x)
    {
        try
        {
            if (listener != null)
            {
                logger.debug("Invoking callback with {} on listener {}",x,listener);
                listener.onException(x);
            }
        }
        catch (Exception xx)
        {
            logger.info("Exception while notifying listener " + listener, xx);
        }
        catch (Error xx)
        {
            logger.info("Exception while notifying listener " + listener, xx);
            throw xx;
        }
    }

    private StreamFrameListener notifyOnSyn(SessionFrameListener listener, Stream stream, SynInfo synInfo)
    {
        try
        {
            if (listener == null)
                return null;
            logger.debug("Invoking callback with {} on listener {}",synInfo,listener);
            return listener.onSyn(stream,synInfo);
        }
        catch (Exception x)
        {
            logger.info("Exception while notifying listener " + listener,x);
            return null;
        }
        catch (Error x)
        {
            logger.info("Exception while notifying listener " + listener, x);
            throw x;
        }
    }

    private void notifyOnRst(SessionFrameListener listener, RstInfo rstInfo)
    {
        try
        {
            if (listener != null)
            {
                logger.debug("Invoking callback with {} on listener {}",rstInfo,listener);
                listener.onRst(this,rstInfo);
            }
        }
        catch (Exception x)
        {
            logger.info("Exception while notifying listener " + listener, x);
        }
        catch (Error x)
        {
            logger.info("Exception while notifying listener " + listener, x);
            throw x;
        }
    }

    private void notifyOnSettings(SessionFrameListener listener, SettingsInfo settingsInfo)
    {
        try
        {
            if (listener != null)
            {
                logger.debug("Invoking callback with {} on listener {}",settingsInfo,listener);
                listener.onSettings(this, settingsInfo);
            }
        }
        catch (Exception x)
        {
            logger.info("Exception while notifying listener " + listener, x);
        }
        catch (Error x)
        {
            logger.info("Exception while notifying listener " + listener, x);
            throw x;
        }
    }

    private void notifyOnPing(SessionFrameListener listener, PingInfo pingInfo)
    {
        try
        {
            if (listener != null)
            {
                logger.debug("Invoking callback with {} on listener {}",pingInfo,listener);
                listener.onPing(this, pingInfo);
            }
        }
        catch (Exception x)
        {
            logger.info("Exception while notifying listener " + listener, x);
        }
        catch (Error x)
        {
            logger.info("Exception while notifying listener " + listener, x);
            throw x;
        }
    }

    private void notifyOnGoAway(SessionFrameListener listener, GoAwayInfo goAwayInfo)
    {
        try
        {
            if (listener != null)
            {
                logger.debug("Invoking callback with {} on listener {}",goAwayInfo,listener);
                listener.onGoAway(this, goAwayInfo);
            }
        }
        catch (Exception x)
        {
            logger.info("Exception while notifying listener " + listener, x);
        }
        catch (Error x)
        {
            logger.info("Exception while notifying listener " + listener, x);
            throw x;
        }
    }

    @Override
    public <C> void control(IStream stream, ControlFrame frame, long timeout, TimeUnit unit, Handler<C> handler, C context)
    {
        generateAndEnqueueControlFrame(stream,frame,timeout,unit,handler,context);
        flush();
    }

    private <C> void generateAndEnqueueControlFrame(IStream stream, ControlFrame frame, long timeout, TimeUnit unit, Handler<C> handler, C context)
    {
        try
        {
            // Synchronization is necessary, since we may have concurrent replies
            // and those needs to be generated and enqueued atomically in order
            // to maintain a correct compression context
            synchronized (this)
            {
                ByteBuffer buffer = generator.control(frame);
                logger.debug("Queuing {} on {}", frame, stream);
                ControlFrameBytes<C> frameBytes = new ControlFrameBytes<>(stream, handler, context, frame, buffer);
                if (timeout > 0)
                    frameBytes.task = scheduler.schedule(frameBytes, timeout, unit);

                // Special handling for PING frames, they must be sent as soon as possible
                if (ControlFrameType.PING == frame.getType())
                    prepend(frameBytes);
                else
                    append(frameBytes);
            }
        }
        catch (Exception x)
        {
            notifyHandlerFailed(handler, context, x);
        }
    }

    private void updateLastStreamId(IStream stream)
    {
        int streamId = stream.getId();
        if (streamId % 2 != streamIds.get() % 2)
            Atomics.updateMax(lastStreamId, streamId);
    }

    @Override
    public <C> void data(IStream stream, DataInfo dataInfo, long timeout, TimeUnit unit, Handler<C> handler, C context)
    {
        logger.debug("Queuing {} on {}",dataInfo,stream);
        DataFrameBytes<C> frameBytes = new DataFrameBytes<>(stream,handler,context,dataInfo);
        if (timeout > 0)
            frameBytes.task = scheduler.schedule(frameBytes,timeout,unit);
        append(frameBytes);
        flush();
    }

    private void execute(Runnable task)
    {
        threadPool.execute(task);
    }

    @Override
    public void flush()
    {
        FrameBytes frameBytes = null;
        ByteBuffer buffer = null;
        synchronized (queue)
        {
            if (flushing || queue.isEmpty())
                return;

            Set<IStream> stalledStreams = null;
            for (int i = 0; i < queue.size(); ++i)
            {
                frameBytes = queue.get(i);

                IStream stream = frameBytes.getStream();
                if (stream != null && stalledStreams != null && stalledStreams.contains(stream))
                    continue;

                buffer = frameBytes.getByteBuffer();
                if (buffer != null)
                {
                    queue.remove(i);
                    if (stream != null && stream.isReset())
                    {
                        frameBytes.fail(new StreamException(stream.getId(),StreamStatus.INVALID_STREAM));
                        return;
                    }
                    break;
                }

                if (stalledStreams == null)
                    stalledStreams = new HashSet<>();
                if (stream != null)
                    stalledStreams.add(stream);

                logger.debug("Flush stalled for {}, {} frame(s) in queue",frameBytes,queue.size());
            }

            if (buffer == null)
                return;

            flushing = true;
            logger.debug("Flushing {}, {} frame(s) in queue",frameBytes,queue.size());
        }
        write(buffer,this,frameBytes);
    }

    private void append(FrameBytes frameBytes)
    {
        Throwable failure;
        synchronized (queue)
        {
            failure = this.failure;
            if (failure == null)
            {
                int index = queue.size();
                while (index > 0)
                {
                    FrameBytes element = queue.get(index - 1);
                    if (element.compareTo(frameBytes) >= 0)
                        break;
                    --index;
                }
                queue.add(index,frameBytes);
            }
        }

        if (failure != null)
            frameBytes.fail(new SPDYException(failure));
    }

    private void prepend(FrameBytes frameBytes)
    {
        Throwable failure;
        synchronized (queue)
        {
            failure = this.failure;
            if (failure == null)
            {
                int index = 0;
                while (index < queue.size())
                {
                    FrameBytes element = queue.get(index);
                    if (element.compareTo(frameBytes) <= 0)
                        break;
                    ++index;
                }
                queue.add(index,frameBytes);
            }
        }

        if (failure != null)
            frameBytes.fail(new SPDYException(failure));
    }

    @Override
    public void completed(FrameBytes frameBytes)
    {
        synchronized (queue)
        {
            logger.debug("Completed write of {}, {} frame(s) in queue",frameBytes,queue.size());
            flushing = false;
        }
        frameBytes.complete();
    }

    @Override
    public void failed(FrameBytes frameBytes, Throwable x)
    {
        List<FrameBytes> frameBytesToFail = new ArrayList<>();
        frameBytesToFail.add(frameBytes);

        synchronized (queue)
        {
            failure = x;
            String logMessage = String.format("Failed write of %s, failing all %d frame(s) in queue",frameBytes,queue.size());
            logger.debug(logMessage,x);
            frameBytesToFail.addAll(queue);
            queue.clear();
            flushing = false;
        }

        for (FrameBytes fb : frameBytesToFail)
            fb.fail(x);
    }

    protected void write(ByteBuffer buffer, Handler<FrameBytes> handler, FrameBytes frameBytes)
    {
        if (controller != null)
        {
            logger.debug("Writing {} frame bytes of {}",buffer.remaining(),frameBytes);
            controller.write(buffer,handler,frameBytes);
        }
    }

    private <C> void complete(final Handler<C> handler, final C context)
    {
        // Applications may send and queue up a lot of frames and
        // if we call Handler.completed() only synchronously we risk
        // starvation (for the last frames sent) and stack overflow.
        // Therefore every some invocation, we dispatch to a new thread
        Integer invocations = handlerInvocations.get();
        if (invocations >= 4)
        {
            execute(new Runnable()
            {
                @Override
                public void run()
                {
                    if (handler != null)
                        notifyHandlerCompleted(handler,context);
                    flush();
                }
            });
        }
        else
        {
            handlerInvocations.set(invocations + 1);
            try
            {
                if (handler != null)
                    notifyHandlerCompleted(handler,context);
                flush();
            }
            finally
            {
                handlerInvocations.set(invocations);
            }
        }
    }

    private <C> void notifyHandlerCompleted(Handler<C> handler, C context)
    {
        try
        {
            handler.completed(context);
        }
        catch (Exception x)
        {
            logger.info("Exception while notifying handler " + handler, x);
        }
        catch (Error x)
        {
            logger.info("Exception while notifying handler " + handler, x);
            throw x;
        }
    }

    private <C> void notifyHandlerFailed(Handler<C> handler, C context, Throwable x)
    {
        try
        {
            if (handler != null)
                handler.failed(context, x);
        }
        catch (Exception xx)
        {
            logger.info("Exception while notifying handler " + handler, xx);
        }
        catch (Error xx)
        {
            logger.info("Exception while notifying handler " + handler, xx);
            throw xx;
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

    public String toString()
    {
        return String.format("%s@%x{v%d,queuSize=%d,windowSize=%d,streams=%d}", getClass().getSimpleName(), hashCode(), version, queue.size(), getWindowSize(), streams.size());
    }
    
    
    @Override
    public String dump()
    {
        return AggregateLifeCycle.dump(this);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        AggregateLifeCycle.dumpObject(out,this);
        AggregateLifeCycle.dump(out,indent,Collections.singletonList(controller),streams.values());
    }



    public interface FrameBytes extends Comparable<FrameBytes>
    {
        public IStream getStream();

        public abstract ByteBuffer getByteBuffer();

        public abstract void complete();

        public abstract void fail(Throwable throwable);
    }

    private abstract class AbstractFrameBytes<C> implements FrameBytes, Runnable
    {
        private final IStream stream;
        private final Handler<C> handler;
        private final C context;
        protected volatile ScheduledFuture<?> task;

        protected AbstractFrameBytes(IStream stream, Handler<C> handler, C context)
        {
            this.stream = stream;
            this.handler = handler;
            this.context = context;
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

        @Override
        public void complete()
        {
            cancelTask();
            StandardSession.this.complete(handler,context);
        }

        @Override
        public void fail(Throwable x)
        {
            cancelTask();
            notifyHandlerFailed(handler,context,x);
            StandardSession.this.flush();
        }

        private void cancelTask()
        {
            ScheduledFuture<?> task = this.task;
            if (task != null)
                task.cancel(false);
        }

        @Override
        public void run()
        {
            close();
            fail(new InterruptedByTimeoutException());
        }
    }

    private class ControlFrameBytes<C> extends AbstractFrameBytes<C>
    {
        private final ControlFrame frame;
        private final ByteBuffer buffer;

        private ControlFrameBytes(IStream stream, Handler<C> handler, C context, ControlFrame frame, ByteBuffer buffer)
        {
            super(stream,handler,context);
            this.frame = frame;
            this.buffer = buffer;
        }

        @Override
        public ByteBuffer getByteBuffer()
        {
            return buffer;
        }

        @Override
        public void complete()
        {
            bufferPool.release(buffer);

            super.complete();

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

    private class DataFrameBytes<C> extends AbstractFrameBytes<C>
    {
        private final DataInfo dataInfo;
        private int size;
        private volatile ByteBuffer buffer;

        private DataFrameBytes(IStream stream, Handler<C> handler, C context, DataInfo dataInfo)
        {
            super(stream,handler,context);
            this.dataInfo = dataInfo;
        }

        @Override
        public ByteBuffer getByteBuffer()
        {
            try
            {
                IStream stream = getStream();
                int windowSize = stream.getWindowSize();
                if (windowSize <= 0)
                    return null;

                size = dataInfo.available();
                if (size > windowSize)
                    size = windowSize;

                buffer = generator.data(stream.getId(),size,dataInfo);
                return buffer;
            }
            catch (Throwable x)
            {
                fail(x);
                return null;
            }
        }

        @Override
        public void complete()
        {
            bufferPool.release(buffer);
            IStream stream = getStream();
            flowControlStrategy.updateWindow(StandardSession.this, stream, -size);
            if (dataInfo.available() > 0)
            {
                // We have written a frame out of this DataInfo, but there is more to write.
                // We need to keep the correct ordering of frames, to avoid that another
                // DataInfo for the same stream is written before this one is finished.
                prepend(this);
                flush();
            }
            else
            {
                super.complete();
                stream.updateCloseState(dataInfo.isClose(),true);
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
}
