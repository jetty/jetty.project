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
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
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
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final ConcurrentMap<Integer, IStream> streams = new ConcurrentHashMap<>();
    private final Deque<FrameBytes> queue = new LinkedList<>();
    private final short version;
    private final Controller<FrameBytes> controller;
    private final AtomicInteger streamIds;
    private final AtomicInteger pingIds;
    private final SessionFrameListener listener;
    private final Generator generator;
    private final AtomicBoolean goAwaySent = new AtomicBoolean();
    private final AtomicBoolean goAwayReceived = new AtomicBoolean();
    private final AtomicInteger lastStreamId = new AtomicInteger();
    private boolean flushing;
    private volatile int windowSize = 65536;

    public StandardSession(short version, Controller<FrameBytes> controller, int initialStreamId, SessionFrameListener listener, Generator generator)
    {
        this.version = version;
        this.controller = controller;
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
        syn(synInfo, listener, result);
        return result;
    }

    @Override
    public void syn(SynInfo synInfo, StreamFrameListener listener, final Handler<Stream> handler)
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
                final IStream stream = createStream(synStream, listener);
                try
                {
                    // May throw if wrong version or headers too big
                    control(stream, synStream, handler, stream);
                    flush();
                }
                catch (StreamException x)
                {
                    removeStream(stream);
                    handler.failed(x);
                }
            }
        }
    }

    @Override
    public Future<Void> rst(RstInfo rstInfo)
    {
        Promise<Void> result = new Promise<>();
        rst(rstInfo, result);
        return result;
    }

    @Override
    public void rst(RstInfo rstInfo, Handler<Void> handler)
    {
        try
        {
            // SPEC v3, 2.2.2
            if (goAwaySent.get())
            {
                handler.completed(null);
            }
            else
            {
                RstStreamFrame frame = new RstStreamFrame(version, rstInfo.getStreamId(), rstInfo.getStreamStatus().getCode(version));
                control(null, frame, handler, null);
                flush();
            }
        }
        catch (StreamException x)
        {
            logger.info("Could not send reset on stream " + rstInfo.getStreamId(), x);
            handler.failed(x);
        }
    }

    @Override
    public Future<Void> settings(SettingsInfo settingsInfo)
    {
        Promise<Void> result = new Promise<>();
        settings(settingsInfo, result);
        return result;
    }

    @Override
    public void settings(SettingsInfo settingsInfo, Handler<Void> handler)
    {
        try
        {
            SettingsFrame frame = new SettingsFrame(version, settingsInfo.getFlags(), settingsInfo.getSettings());
            control(null, frame, handler, null);
            flush();
        }
        catch (StreamException x)
        {
            handler.failed(x);
        }
    }

    @Override
    public Future<PingInfo> ping()
    {
        Promise<PingInfo> result = new Promise<>();
        ping(result);
        return result;
    }

    @Override
    public void ping(final Handler<PingInfo> handler)
    {
        int pingId = pingIds.getAndAdd(2);
        PingInfo pingInfo = new PingInfo(pingId);
        try
        {
            PingFrame frame = new PingFrame(version, pingId);
            control(null, frame, handler, pingInfo);
            flush();
        }
        catch (StreamException x)
        {
            handler.failed(x);
        }
    }

    @Override
    public Future<Void> goAway()
    {
        Promise<Void> result = new Promise<>();
        goAway(result);
        return result;
    }

    @Override
    public void goAway(Handler<Void> handler)
    {
        if (goAwaySent.compareAndSet(false, true))
        {
            if (!goAwayReceived.get())
            {
                try
                {
                    GoAwayFrame frame = new GoAwayFrame(version, lastStreamId.get(), SessionStatus.OK.getCode());
                    control(null, frame, handler, null);
                    flush();
                    return;
                }
                catch (StreamException x)
                {
                    handler.failed(x);
                }
            }
        }
        handler.completed(null);
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

    @Override
    public void onDataFrame(DataFrame frame, ByteBuffer data)
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
            rst(new RstInfo(streamId, StreamStatus.INVALID_STREAM));
        }
        else
        {
            stream.handle(frame, data);
            flush();

            if (stream.isClosed())
            {
                updateLastStreamId(stream);
                removeStream(stream);
            }
        }
    }

    @Override
    public void onStreamException(StreamException x)
    {
        // TODO: must send a RST_STREAM on the proper stream... too little information in StreamException
    }

    @Override
    public void onSessionException(SessionException x)
    {
        // TODO: must send a GOAWAY with the x.sessionStatus, then close

        // Check for null to support tests
        if (controller != null)
            controller.close(true);
    }

    private void onSyn(SynStreamFrame synStream)
    {
        IStream stream = new StandardStream(this, synStream);
        logger.debug("Opening {}", stream);
        int streamId = synStream.getStreamId();
        Stream existing = streams.putIfAbsent(streamId, stream);
        if (existing != null)
        {
            logger.debug("Detected duplicate {}, resetting", stream);
            rst(new RstInfo(streamId, StreamStatus.PROTOCOL_ERROR));
        }
        else
        {
            stream.handle(synStream);
            StreamFrameListener listener = notifyOnSyn(stream, synStream);
            stream.setStreamFrameListener(listener);

            flush();

            // The onSyn() listener may have sent a frame that closed the stream
            if (stream.isClosed())
                removeStream(stream);
        }
    }

    private IStream createStream(SynStreamFrame synStream, StreamFrameListener listener)
    {
        IStream stream = new StandardStream(this, synStream);
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
        stream.handle(frame);
        flush();
        if (stream.isClosed())
            removeStream(stream);
    }

    private void onRst(RstStreamFrame frame)
    {
        // TODO: implement logic to clean up unidirectional streams associated with this stream

        notifyOnRst(frame);

        int streamId = frame.getStreamId();
        IStream stream = streams.get(streamId);
        if (stream != null)
            removeStream(stream);
    }

    private void onSettings(SettingsFrame frame)
    {
        Settings.Setting windowSizeSetting = frame.getSettings().get(Settings.ID.INITIAL_WINDOW_SIZE);
        if (windowSizeSetting != null)
            this.windowSize = windowSizeSetting.getValue();
        notifyOnSettings(frame);
        flush();
    }

    private void onPing(PingFrame frame)
    {
        try
        {
            int pingId = frame.getPingId();
            if (pingId % 2 == pingIds.get() % 2)
                notifyOnPing(frame);
            else
                control(null, frame, new Promise<>(), null);
            flush();
        }
        catch (StreamException x)
        {
            throw new SPDYException(x);
        }
    }

    private void onGoAway(GoAwayFrame frame)
    {
        if (goAwayReceived.compareAndSet(false, true))
        {
            notifyOnGoAway(frame);
            flush();

            // SPDY does not require to send back a response to a GO_AWAY.
            // We notified the application of the last good stream id,
            // tried our best to flush remaining data, and close.
            controller.close(false);
        }
    }

    private void onHeaders(HeadersFrame frame)
    {
        int streamId = frame.getStreamId();
        IStream stream = streams.get(streamId);
        stream.handle(frame);
        flush();
        if (stream.isClosed())
            removeStream(stream);
    }

    private void onWindowUpdate(WindowUpdateFrame frame)
    {
        int streamId = frame.getStreamId();
        IStream stream = streams.get(streamId);
        if (stream != null)
            stream.handle(frame);
        flush();
    }

    private StreamFrameListener notifyOnSyn(Stream stream, SynStreamFrame frame)
    {
        try
        {
            if (listener != null)
            {
                logger.debug("Invoking callback with {} on listener {}", frame, listener);
                SynInfo synInfo = new SynInfo(frame.getHeaders(), frame.isClose(), frame.isUnidirectional(), frame.getAssociatedStreamId(), frame.getPriority());
                return listener.onSyn(stream, synInfo);
            }
        }
        catch (Exception x)
        {
            logger.info("Exception while notifying listener " + listener, x);
        }
        return null;
    }

    private void notifyOnRst(RstStreamFrame frame)
    {
        try
        {
            if (listener != null)
            {
                logger.debug("Invoking callback with {} on listener {}", frame, listener);
                RstInfo rstInfo = new RstInfo(frame.getStreamId(), StreamStatus.from(frame.getVersion(), frame.getStatusCode()));
                listener.onRst(this, rstInfo);
            }
        }
        catch (Exception x)
        {
            logger.info("Exception while notifying listener " + listener, x);
        }
    }

    private void notifyOnSettings(SettingsFrame frame)
    {
        try
        {
            if (listener != null)
            {
                logger.debug("Invoking callback with {} on listener {}", frame, listener);
                SettingsInfo settingsInfo = new SettingsInfo(frame.getSettings(), frame.isClearPersisted());
                listener.onSettings(this, settingsInfo);
            }
        }
        catch (Exception x)
        {
            logger.info("Exception while notifying listener " + listener, x);
        }
    }

    private void notifyOnPing(final PingFrame frame)
    {
        try
        {
            if (listener != null)
            {
                logger.debug("Invoking callback with {} on listener {}", frame, listener);
                PingInfo pingInfo = new PingInfo(frame.getPingId());
                listener.onPing(this, pingInfo);
            }
        }
        catch (Exception x)
        {
            logger.info("Exception while notifying listener " + listener, x);
        }
    }

    private void notifyOnGoAway(GoAwayFrame frame)
    {
        try
        {
            if (listener != null)
            {
                logger.debug("Invoking callback with {} on listener {}", frame, listener);
                GoAwayInfo goAwayInfo = new GoAwayInfo(frame.getLastStreamId(), SessionStatus.from(frame.getStatusCode()));
                listener.onGoAway(this, goAwayInfo);
            }
        }
        catch (Exception x)
        {
            logger.info("Exception while notifying listener " + listener, x);
        }
    }

    @Override
    public <C> void control(IStream stream, ControlFrame frame, Handler<C> handler, C context) throws StreamException
    {
        if (stream != null)
            updateLastStreamId(stream);
        ByteBuffer buffer = generator.control(frame);
        logger.debug("Posting {}", frame);
        enqueueLast(new ControlFrameBytes<>(frame, buffer, handler, context));
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
    public <C> void data(IStream stream, DataInfo dataInfo, Handler<C> handler, C context)
    {
        logger.debug("Posting {} on {}", dataInfo, stream);
        enqueueLast(new DataFrameBytes<>(stream, dataInfo, handler, context));
        flush();
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
        flush();
    }

    @Override
    public void failed(Throwable x)
    {
        throw new SPDYException(x);
    }

    protected void write(final ByteBuffer buffer, Handler<FrameBytes> handler, FrameBytes frameBytes)
    {
        controller.write(buffer, handler, frameBytes);
    }

    public interface FrameBytes
    {
        public abstract ByteBuffer getByteBuffer();

        public abstract void complete();
    }

    private class ControlFrameBytes<C> implements FrameBytes
    {
        private final ControlFrame frame;
        private final ByteBuffer buffer;
        private final Handler<C> handler;
        private final C context;

        private ControlFrameBytes(ControlFrame frame, ByteBuffer buffer, Handler<C> handler, C context)
        {
            this.frame = frame;
            this.buffer = buffer;
            this.handler = handler;
            this.context = context;
        }

        @Override
        public ByteBuffer getByteBuffer()
        {
            return buffer;
        }

        @Override
        public void complete()
        {
            if (frame.getType() == ControlFrameType.GO_AWAY)
            {
                // After sending a GO_AWAY we need to hard close the connection.
                // Recipients will know the last good stream id and act accordingly.
                controller.close(false);
            }
            handler.completed(context);
        }

        @Override
        public String toString()
        {
            return frame.toString();
        }
    }

    private class DataFrameBytes<C> implements FrameBytes
    {
        private final IStream stream;
        private final DataInfo data;
        private final Handler<C> handler;
        private final C context;
        private int dataLength;

        private DataFrameBytes(IStream stream, DataInfo data, Handler<C> handler, C context)
        {
            this.stream = stream;
            this.data = data;
            this.handler = handler;
            this.context = context;
        }

        @Override
        public ByteBuffer getByteBuffer()
        {
            int windowSize = stream.getWindowSize();
            if (windowSize <= 0)
                return null;

            ByteBuffer buffer = generator.data(stream.getId(), windowSize, data);
            dataLength = buffer.remaining() - DataFrame.HEADER_LENGTH;

            return buffer;
        }

        @Override
        public void complete()
        {
            stream.updateWindowSize(-dataLength);

            if (!data.isConsumed())
            {
                // If we could not write a full data frame, then we need first
                // to finish it, and then process the others (to avoid data garbling)
                enqueueFirst(this);
            }
            else
            {
                stream.updateCloseState(data.isClose());
                if (stream.isClosed())
                    removeStream(stream);
                handler.completed(context);
            }
        }

        @Override
        public String toString()
        {
            return String.format("DATA bytes @%x consumed=%b on %s", data.hashCode(), data.isConsumed(), stream);
        }
    }
}
