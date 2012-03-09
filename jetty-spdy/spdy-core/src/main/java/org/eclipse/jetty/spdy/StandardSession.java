/*
 * Copyright (c) 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.eclipse.jetty.spdy;

import java.nio.ByteBuffer;
import java.nio.channels.InterruptedByTimeoutException;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StandardSession implements ISession, Parser.Listener, Handler<StandardSession.FrameBytes>
{
    private static final Logger logger = LoggerFactory.getLogger(Session.class);
    private static final ThreadLocal<Integer> handlerInvocations = new ThreadLocal<Integer>()
    {
        @Override
        protected Integer initialValue()
        {
            return 0;
        }
    };

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final ConcurrentMap<Integer, IStream> streams = new ConcurrentHashMap<>();
    private final Deque<FrameBytes> queue = new LinkedList<>();
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
    private boolean flushing;
    private volatile int windowSize = 65536;

    public StandardSession(short version, ByteBufferPool bufferPool, Executor threadPool, ScheduledExecutorService scheduler, Controller<FrameBytes> controller, IdleListener idleListener, int initialStreamId, SessionFrameListener listener, Generator generator)
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
    }

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
        syn(synInfo, listener, 0, TimeUnit.MILLISECONDS, result);
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
        synchronized (this)
        {
            if (synInfo.isUnidirectional())
            {
                // TODO: unidirectional functionality
                throw new UnsupportedOperationException();
            }
            else
            {
                int streamId = streamIds.getAndAdd(2);
                SynStreamFrame synStream = new SynStreamFrame(version, synInfo.getFlags(), streamId, 0, synInfo.getPriority(), synInfo.getHeaders());
                IStream stream = createStream(synStream, listener);
                control(stream, synStream, timeout, unit, handler, stream);
            }
        }
    }

    @Override
    public Future<Void> rst(RstInfo rstInfo)
    {
        Promise<Void> result = new Promise<>();
        rst(rstInfo, 0, TimeUnit.MILLISECONDS, result);
        return result;
    }

    @Override
    public void rst(RstInfo rstInfo, long timeout, TimeUnit unit, Handler<Void> handler)
    {
        // SPEC v3, 2.2.2
        if (goAwaySent.get())
        {
            complete(handler, null);
        }
        else
        {
            int streamId = rstInfo.getStreamId();
            IStream stream = streams.get(streamId);
            if (stream != null)
                removeStream(stream);
            RstStreamFrame frame = new RstStreamFrame(version, streamId, rstInfo.getStreamStatus().getCode(version));
            control(null, frame, timeout, unit, handler, null);
        }
    }

    @Override
    public Future<Void> settings(SettingsInfo settingsInfo)
    {
        Promise<Void> result = new Promise<>();
        settings(settingsInfo, 0, TimeUnit.MILLISECONDS, result);
        return result;
    }

    @Override
    public void settings(SettingsInfo settingsInfo, long timeout, TimeUnit unit, Handler<Void> handler)
    {
        SettingsFrame frame = new SettingsFrame(version, settingsInfo.getFlags(), settingsInfo.getSettings());
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
        PingFrame frame = new PingFrame(version, pingId);
        control(null, frame, timeout, unit, handler, pingInfo);
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
        if (goAwaySent.compareAndSet(false, true))
        {
            if (!goAwayReceived.get())
            {
                GoAwayFrame frame = new GoAwayFrame(version, lastStreamId.get(), sessionStatus.getCode());
                control(null, frame, timeout, unit, handler, null);
                return;
            }
        }
        complete(handler, null);
    }

    @Override
    public List<Stream> getStreams()
    {
        List<Stream> result = new ArrayList<>();
        result.addAll(streams.values());
        return result;
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

    private void processData(IStream stream, DataFrame frame, ByteBuffer data)
    {
        stream.process(frame, data);
        updateLastStreamId(stream);
        if (stream.isClosed())
            removeStream(stream);
    }

    @Override
    public void onStreamException(StreamException x)
    {
        notifyOnException(listener, x);
        rst(new RstInfo(x.getStreamId(), x.getStreamStatus()));
    }

    @Override
    public void onSessionException(SessionException x)
    {
        Throwable cause = x.getCause();
        notifyOnException(listener, cause == null ? x : cause);
        goAway(x.getSessionStatus());
    }

    private void onSyn(SynStreamFrame frame)
    {
        IStream stream = newStream(frame);
        logger.debug("Opening {}", stream);
        int streamId = frame.getStreamId();
        IStream existing = streams.putIfAbsent(streamId, stream);
        if (existing != null)
        {
            RstInfo rstInfo = new RstInfo(streamId, StreamStatus.PROTOCOL_ERROR);
            logger.debug("Duplicate stream, {}", rstInfo);
            rst(rstInfo);
        }
        else
        {
            processSyn(listener, stream, frame);
        }
    }

    private void processSyn(SessionFrameListener listener, IStream stream, SynStreamFrame frame)
    {
        stream.process(frame);
        SynInfo synInfo = new SynInfo(frame.getHeaders(), frame.isClose(),
                frame.isUnidirectional(), frame.getAssociatedStreamId(), frame.getPriority());
        StreamFrameListener streamListener = notifyOnSyn(listener, stream, synInfo);
        stream.setStreamFrameListener(streamListener);
        flush();
        // The onSyn() listener may have sent a frame that closed the stream
        if (stream.isClosed())
            removeStream(stream);
    }

    private IStream createStream(SynStreamFrame synStream, StreamFrameListener listener)
    {
        IStream stream = newStream(synStream);
        stream.setStreamFrameListener(listener);
        if (streams.putIfAbsent(synStream.getStreamId(), stream) != null)
        {
            // If this happens we have a bug since we did not check that the peer's streamId was valid
            // (if we're on server, then the client sent an odd streamId and we did not check that)
            throw new IllegalStateException();
        }

        logger.debug("Created {}", stream);
        notifyStreamCreated(stream);

        return stream;
    }

    private IStream newStream(SynStreamFrame frame)
    {
        return new StandardStream(frame, this, windowSize);
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
            }
        }
    }

    private void removeStream(IStream stream)
    {
        IStream removed = streams.remove(stream.getId());
        if (removed != null)
        {
            assert removed == stream;
            logger.debug("Removed {}", stream);
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
                    logger.info("Exception while notifying listener " + listener, x);
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
            logger.debug("Unknown stream {}", rstInfo);
            rst(rstInfo);
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
        // TODO: implement logic to clean up unidirectional streams associated with this stream

        IStream stream = streams.get(frame.getStreamId());

        if (stream != null)
            stream.process(frame);

        RstInfo rstInfo = new RstInfo(frame.getStreamId(), StreamStatus.from(frame.getVersion(), frame.getStatusCode()));
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
            windowSize = windowSizeSetting.value();
            logger.debug("Updated window size to {}", windowSize);
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
            GoAwayInfo goAwayInfo = new GoAwayInfo(frame.getLastStreamId(), SessionStatus.from(frame.getStatusCode()));
            notifyOnGoAway(listener, goAwayInfo);
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
            RstInfo rstInfo = new RstInfo(streamId, StreamStatus.INVALID_STREAM);
            logger.debug("Unknown stream, {}", rstInfo);
            rst(rstInfo);
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
        if (stream != null)
            stream.process(frame);
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
                logger.debug("Invoking callback with {} on listener {}", x, listener);
                listener.onException(x);
            }
        }
        catch (Exception xx)
        {
            logger.info("Exception while notifying listener " + listener, xx);
        }
    }

    private StreamFrameListener notifyOnSyn(SessionFrameListener listener, Stream stream, SynInfo synInfo)
    {
        try
        {
            if (listener != null)
            {
                logger.debug("Invoking callback with {} on listener {}", synInfo, listener);
                return listener.onSyn(stream, synInfo);
            }
        }
        catch (Exception x)
        {
            logger.info("Exception while notifying listener " + listener, x);
        }
        return null;
    }

    private void notifyOnRst(SessionFrameListener listener, RstInfo rstInfo)
    {
        try
        {
            if (listener != null)
            {
                logger.debug("Invoking callback with {} on listener {}", rstInfo, listener);
                listener.onRst(this, rstInfo);
            }
        }
        catch (Exception x)
        {
            logger.info("Exception while notifying listener " + listener, x);
        }
    }

    private void notifyOnSettings(SessionFrameListener listener, SettingsInfo settingsInfo)
    {
        try
        {
            if (listener != null)
            {
                logger.debug("Invoking callback with {} on listener {}", settingsInfo, listener);
                listener.onSettings(this, settingsInfo);
            }
        }
        catch (Exception x)
        {
            logger.info("Exception while notifying listener " + listener, x);
        }
    }

    private void notifyOnPing(SessionFrameListener listener, PingInfo pingInfo)
    {
        try
        {
            if (listener != null)
            {
                logger.debug("Invoking callback with {} on listener {}", pingInfo, listener);
                listener.onPing(this, pingInfo);
            }
        }
        catch (Exception x)
        {
            logger.info("Exception while notifying listener " + listener, x);
        }
    }

    private void notifyOnGoAway(SessionFrameListener listener, GoAwayInfo goAwayInfo)
    {
        try
        {
            if (listener != null)
            {
                logger.debug("Invoking callback with {} on listener {}", goAwayInfo, listener);
                listener.onGoAway(this, goAwayInfo);
            }
        }
        catch (Exception x)
        {
            logger.info("Exception while notifying listener " + listener, x);
        }
    }

    @Override
    public <C> void control(IStream stream, ControlFrame frame, long timeout, TimeUnit unit, Handler<C> handler, C context)
    {
        try
        {
            if (stream != null)
                updateLastStreamId(stream);

            // Synchronization is necessary, since we may have concurrent replies
            // and those needs to be generated and enqueued atomically in order
            // to maintain a correct compression context
            synchronized (this)
            {
                ByteBuffer buffer = generator.control(frame);
                logger.debug("Queuing {} on {}", frame, stream);
                ControlFrameBytes<C> frameBytes = new ControlFrameBytes<>(handler, context, frame, buffer);
                if (timeout > 0)
                    frameBytes.task = scheduler.schedule(frameBytes, timeout, unit);
                enqueueLast(frameBytes);
            }

            flush();
        }
        catch (Throwable x)
        {
            notifyHandlerFailed(handler, x);
        }
    }

    private void updateLastStreamId(IStream stream)
    {
        int streamId = stream.getId();
        if (stream.isClosed() && streamId % 2 != streamIds.get() % 2)
        {
            // Non-blocking atomic update
            int oldValue = lastStreamId.get();
            while (streamId > oldValue)
            {
                if (lastStreamId.compareAndSet(oldValue, streamId))
                    break;
                oldValue = lastStreamId.get();
            }
        }
    }

    @Override
    public <C> void data(IStream stream, DataInfo dataInfo, long timeout, TimeUnit unit, Handler<C> handler, C context)
    {
        logger.debug("Queuing {} on {}", dataInfo, stream);
        DataFrameBytes<C> frameBytes = new DataFrameBytes<>(handler, context, stream, dataInfo);
        if (timeout > 0)
            frameBytes.task = scheduler.schedule(frameBytes, timeout, unit);
        enqueueLast(frameBytes);
        flush();
    }

    private void execute(Runnable task)
    {
        threadPool.execute(task);
    }

    @Override
    public int getWindowSize()
    {
        return windowSize;
    }

    @Override
    public void flush()
    {
        FrameBytes frameBytes;
        ByteBuffer buffer;
        synchronized (queue)
        {
            if (flushing)
                return;

            frameBytes = queue.poll();
            if (frameBytes == null)
                return;

            buffer = frameBytes.getByteBuffer();
            if (buffer == null)
            {
                enqueueFirst(frameBytes);
                logger.debug("Flush skipped, {} frame(s) in queue", queue.size());
                return;
            }

            flushing = true;
            logger.debug("Flushing {}, {} frame(s) in queue", frameBytes, queue.size());
        }

        logger.debug("Writing {} frame bytes of {}", buffer.remaining(), frameBytes);
        write(buffer, this, frameBytes);
    }

    private void enqueueLast(FrameBytes frameBytes)
    {
        // TODO: handle priority; e.g. use queues to prioritize the buffers ?
        synchronized (queue)
        {
            queue.offerLast(frameBytes);
        }
    }

    private void enqueueFirst(FrameBytes frameBytes)
    {
        synchronized (queue)
        {
            queue.offerFirst(frameBytes);
        }
    }

    @Override
    public void completed(FrameBytes frameBytes)
    {
        synchronized (queue)
        {
            logger.debug("Completed write of {}, {} frame(s) in queue", frameBytes, queue.size());
            flushing = false;
        }
        frameBytes.complete();
    }

    @Override
    public void failed(Throwable x)
    {
        throw new SPDYException(x);
    }

    protected void write(ByteBuffer buffer, Handler<FrameBytes> handler, FrameBytes frameBytes)
    {
        if (controller != null)
            controller.write(buffer, handler, frameBytes);
    }

    private <C> void complete(final Handler<C> handler, final C context)
    {
        if (handler != null)
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
                        notifyHandlerCompleted(handler, context);
                        flush();
                    }
                });
            }
            else
            {
                handlerInvocations.set(invocations + 1);
                try
                {
                    notifyHandlerCompleted(handler, context);
                    flush();
                }
                finally
                {
                    handlerInvocations.set(invocations);
                }
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
    }


    private void notifyHandlerFailed(Handler handler, Throwable x)
    {
        try
        {
            if (handler != null)
                handler.failed(x);
        }
        catch (Exception xx)
        {
            logger.info("Exception while notifying handler " + handler, xx);
        }
    }

    public interface FrameBytes
    {
        public abstract ByteBuffer getByteBuffer();

        public abstract void complete();
    }

    private abstract class AbstractFrameBytes<C> implements FrameBytes, Runnable
    {
        private final Handler<C> handler;
        private final C context;
        protected volatile ScheduledFuture<?> task;

        protected AbstractFrameBytes(Handler<C> handler, C context)
        {
            this.handler = handler;
            this.context = context;
        }

        @Override
        public void complete()
        {
            ScheduledFuture<?> task = this.task;
            if (task != null)
                task.cancel(false);
            StandardSession.this.complete(handler, context);
        }

        protected void fail(Throwable x)
        {
            notifyHandlerFailed(handler, x);
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

        private ControlFrameBytes(Handler<C> handler, C context, ControlFrame frame, ByteBuffer buffer)
        {
            super(handler, context);
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
        }

        @Override
        public String toString()
        {
            return frame.toString();
        }
    }

    private class DataFrameBytes<C> extends AbstractFrameBytes<C>
    {
        private final IStream stream;
        private final DataInfo dataInfo;
        private int length;
        private ByteBuffer buffer;

        private DataFrameBytes(Handler<C> handler, C context, IStream stream, DataInfo dataInfo)
        {
            super(handler, context);
            this.stream = stream;
            this.dataInfo = dataInfo;
        }

        @Override
        public ByteBuffer getByteBuffer()
        {
            try
            {
                int windowSize = stream.getWindowSize();
                if (windowSize <= 0)
                    return null;

                length = dataInfo.length();
                if (length > windowSize)
                    length = windowSize;

                buffer = generator.data(stream.getId(), length, dataInfo);
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
            stream.updateWindowSize(-length);
            bufferPool.release(buffer);

            if (dataInfo.available() > 0)
            {
                // If we could not write a full data frame, then we need first
                // to finish it, and then process the others (to avoid data garbling)
                enqueueFirst(this);
            }
            else
            {
                super.complete();
                stream.updateCloseState(dataInfo.isClose());
                if (stream.isClosed())
                    removeStream(stream);
            }
        }

        @Override
        public String toString()
        {
            return String.format("DATA bytes @%x available=%d consumed=%d on %s", dataInfo.hashCode(), dataInfo.available(), dataInfo.consumed(), stream);
        }
    }
}
