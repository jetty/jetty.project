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

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.io.IdleTimeout;
import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.HeadersInfo;
import org.eclipse.jetty.spdy.api.PushInfo;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.RstInfo;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.StreamStatus;
import org.eclipse.jetty.spdy.frames.ControlFrame;
import org.eclipse.jetty.spdy.frames.HeadersFrame;
import org.eclipse.jetty.spdy.frames.SynReplyFrame;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Scheduler;

public class StandardStream extends IdleTimeout implements IStream
{
    private static final Logger LOG = Log.getLogger(Stream.class);
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    private final int id;
    private final byte priority;
    private final ISession session;
    private final IStream associatedStream;
    private final Promise<Stream> promise;
    private final AtomicInteger windowSize = new AtomicInteger();
    private final Set<Stream> pushedStreams = Collections.newSetFromMap(new ConcurrentHashMap<Stream, Boolean>());
    private volatile StreamFrameListener listener;
    private volatile OpenState openState = OpenState.SYN_SENT;
    private volatile CloseState closeState = CloseState.OPENED;
    private volatile boolean reset = false;

    public StandardStream(int id, byte priority, ISession session, IStream associatedStream, Scheduler scheduler, Promise<Stream> promise)
    {
        super(scheduler);
        this.id = id;
        this.priority = priority;
        this.session = session;
        this.associatedStream = associatedStream;
        this.promise = promise;
    }

    @Override
    public int getId()
    {
        return id;
    }

    @Override
    public IStream getAssociatedStream()
    {
        return associatedStream;
    }

    @Override
    public Set<Stream> getPushedStreams()
    {
        return pushedStreams;
    }

    @Override
    public void associate(IStream stream)
    {
        pushedStreams.add(stream);
    }

    @Override
    public void disassociate(IStream stream)
    {
        pushedStreams.remove(stream);
    }

    @Override
    public byte getPriority()
    {
        return priority;
    }

    @Override
    protected void onIdleExpired(TimeoutException timeout)
    {
        StreamFrameListener listener = this.listener;
        if (listener != null)
            listener.onFailure(this, timeout);
        // The stream is now gone, we must close it to
        // avoid that its idle timeout is rescheduled.
        close();
    }

    private void close()
    {
        closeState = CloseState.CLOSED;
        onClose();
    }

    @Override
    public boolean isOpen()
    {
        return !isClosed();
    }

    @Override
    public int getWindowSize()
    {
        return windowSize.get();
    }

    @Override
    public void updateWindowSize(int delta)
    {
        int size = windowSize.addAndGet(delta);
        LOG.debug("Updated window size {} -> {} for {}", size - delta, size, this);
    }

    @Override
    public ISession getSession()
    {
        return session;
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
    public void setStreamFrameListener(StreamFrameListener listener)
    {
        this.listener = listener;
    }

    @Override
    public StreamFrameListener getStreamFrameListener()
    {
        return listener;
    }

    @Override
    public void updateCloseState(boolean close, boolean local)
    {
        LOG.debug("{} close={} local={}", this, close, local);
        if (close)
        {
            switch (closeState)
            {
                case OPENED:
                {
                    closeState = local ? CloseState.LOCALLY_CLOSED : CloseState.REMOTELY_CLOSED;
                    break;
                }
                case LOCALLY_CLOSED:
                {
                    if (local)
                        throw new IllegalStateException();
                    else
                        close();
                    break;
                }
                case REMOTELY_CLOSED:
                {
                    if (local)
                        close();
                    else
                        throw new IllegalStateException();
                    break;
                }
                default:
                {
                    LOG.warn("Already CLOSED! {} local={}", this, local);
                }
            }
        }
    }

    @Override
    public void process(ControlFrame frame)
    {
        notIdle();
        switch (frame.getType())
        {
            case SYN_STREAM:
            {
                openState = OpenState.SYN_RECV;
                break;
            }
            case SYN_REPLY:
            {
                openState = OpenState.REPLY_RECV;
                SynReplyFrame synReply = (SynReplyFrame)frame;
                updateCloseState(synReply.isClose(), false);
                ReplyInfo replyInfo = new ReplyInfo(synReply.getHeaders(), synReply.isClose());
                notifyOnReply(replyInfo);
                break;
            }
            case HEADERS:
            {
                HeadersFrame headers = (HeadersFrame)frame;
                updateCloseState(headers.isClose(), false);
                HeadersInfo headersInfo = new HeadersInfo(headers.getHeaders(), headers.isClose(), headers.isResetCompression());
                notifyOnHeaders(headersInfo);
                break;
            }
            case RST_STREAM:
            {
                reset = true;
                break;
            }
            default:
            {
                throw new IllegalStateException();
            }
        }
    }

    @Override
    public void process(DataInfo dataInfo)
    {
        notIdle();
        // TODO: in v3 we need to send a rst instead of just ignoring
        // ignore data frame if this stream is remotelyClosed already
        if (isRemotelyClosed())
        {
            LOG.debug("Stream is remotely closed, ignoring {}", dataInfo);
            return;
        }

        if (!canReceive())
        {
            LOG.debug("Protocol error receiving {}, resetting", dataInfo);
            session.rst(new RstInfo(getId(), StreamStatus.PROTOCOL_ERROR), Callback.Adapter.INSTANCE);
            return;
        }

        updateCloseState(dataInfo.isClose(), false);
        notifyOnData(dataInfo);
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

    private void notifyOnReply(ReplyInfo replyInfo)
    {
        final StreamFrameListener listener = this.listener;
        try
        {
            if (listener != null)
            {
                LOG.debug("Invoking reply callback with {} on listener {}", replyInfo, listener);
                listener.onReply(this, replyInfo);
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

    private void notifyOnHeaders(HeadersInfo headersInfo)
    {
        final StreamFrameListener listener = this.listener;
        try
        {
            if (listener != null)
            {
                LOG.debug("Invoking headers callback with {} on listener {}", headersInfo, listener);
                listener.onHeaders(this, headersInfo);
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

    private void notifyOnData(DataInfo dataInfo)
    {
        final StreamFrameListener listener = this.listener;
        try
        {
            if (listener != null)
            {
                LOG.debug("Invoking data callback with {} on listener {}", dataInfo, listener);
                listener.onData(this, dataInfo);
                LOG.debug("Invoked data callback with {} on listener {}", dataInfo, listener);
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
    public Stream push(PushInfo pushInfo) throws InterruptedException, ExecutionException, TimeoutException
    {
        FuturePromise<Stream> result = new FuturePromise<>();
        push(pushInfo, result);
        if (pushInfo.getTimeout() > 0)
            return result.get(pushInfo.getTimeout(), pushInfo.getUnit());
        else
            return result.get();
    }

    @Override
    public void push(PushInfo pushInfo, Promise<Stream> promise)
    {
        notIdle();
        if (isClosed() || isReset())
        {
            close();
            promise.failed(new StreamException(getId(), StreamStatus.STREAM_ALREADY_CLOSED,
                    "Stream: " + this + " already closed or reset!"));
            return;
        }
        PushSynInfo pushSynInfo = new PushSynInfo(getId(), pushInfo);
        session.syn(pushSynInfo, null, new StreamPromise(promise));
    }

    @Override
    public void reply(ReplyInfo replyInfo) throws InterruptedException, ExecutionException, TimeoutException
    {
        FutureCallback result = new FutureCallback();
        reply(replyInfo, result);
        if (replyInfo.getTimeout() > 0)
            result.get(replyInfo.getTimeout(), replyInfo.getUnit());
        else
            result.get();
    }

    @Override
    public void reply(ReplyInfo replyInfo, Callback callback)
    {
        notIdle();
        if (isUnidirectional())
        {
            close();
            throw new IllegalStateException("Protocol violation: cannot send SYN_REPLY frames in unidirectional streams");
        }
        openState = OpenState.REPLY_SENT;
        updateCloseState(replyInfo.isClose(), true);
        SynReplyFrame frame = new SynReplyFrame(session.getVersion(), replyInfo.getFlags(), getId(), replyInfo.getHeaders());
        session.control(this, frame, replyInfo.getTimeout(), replyInfo.getUnit(), new StreamCallback(callback));
    }

    @Override
    public void data(DataInfo dataInfo) throws InterruptedException, ExecutionException, TimeoutException
    {
        FutureCallback result = new FutureCallback();
        data(dataInfo, result);
        if (dataInfo.getTimeout() > 0)
            result.get(dataInfo.getTimeout(), dataInfo.getUnit());
        else
            result.get();
    }

    @Override
    public void data(DataInfo dataInfo, Callback callback)
    {
        notIdle();
        if (!canSend())
        {
            session.rst(new RstInfo(getId(), StreamStatus.PROTOCOL_ERROR), new StreamCallback());
            throw new IllegalStateException("Protocol violation: cannot send a DATA frame before a SYN_REPLY frame");
        }
        if (isLocallyClosed())
        {
            session.rst(new RstInfo(getId(), StreamStatus.PROTOCOL_ERROR), new StreamCallback());
            throw new IllegalStateException("Protocol violation: cannot send a DATA frame on a locally closed stream");
        }

        // Cannot update the close state here, because the data that we send may
        // be flow controlled, so we need the stream to update the window size.
        session.data(this, dataInfo, dataInfo.getTimeout(), dataInfo.getUnit(), new StreamCallback(callback));
    }

    @Override
    public void headers(HeadersInfo headersInfo) throws InterruptedException, ExecutionException, TimeoutException
    {
        FutureCallback result = new FutureCallback();
        headers(headersInfo, result);
        if (headersInfo.getTimeout() > 0)
            result.get(headersInfo.getTimeout(), headersInfo.getUnit());
        else
            result.get();
    }

    @Override
    public void headers(HeadersInfo headersInfo, Callback callback)
    {
        notIdle();
        if (!canSend())
        {
            session.rst(new RstInfo(getId(), StreamStatus.PROTOCOL_ERROR), new StreamCallback());
            throw new IllegalStateException("Protocol violation: cannot send a HEADERS frame before a SYN_REPLY frame");
        }
        if (isLocallyClosed())
        {
            session.rst(new RstInfo(getId(), StreamStatus.PROTOCOL_ERROR), new StreamCallback());
            throw new IllegalStateException("Protocol violation: cannot send a HEADERS frame on a closed stream");
        }

        updateCloseState(headersInfo.isClose(), true);
        HeadersFrame frame = new HeadersFrame(session.getVersion(), headersInfo.getFlags(), getId(), headersInfo.getHeaders());
        session.control(this, frame, headersInfo.getTimeout(), headersInfo.getUnit(), new StreamCallback(callback));
    }

    @Override
    public boolean isUnidirectional()
    {
        return associatedStream != null;
    }

    @Override
    public boolean isReset()
    {
        return reset;
    }

    @Override
    public boolean isHalfClosed()
    {
        CloseState closeState = this.closeState;
        return closeState == CloseState.LOCALLY_CLOSED || closeState == CloseState.REMOTELY_CLOSED || closeState == CloseState.CLOSED;
    }

    @Override
    public boolean isClosed()
    {
        return closeState == CloseState.CLOSED;
    }

    private boolean isLocallyClosed()
    {
        CloseState closeState = this.closeState;
        return closeState == CloseState.LOCALLY_CLOSED || closeState == CloseState.CLOSED;
    }

    private boolean isRemotelyClosed()
    {
        CloseState closeState = this.closeState;
        return closeState == CloseState.REMOTELY_CLOSED || closeState == CloseState.CLOSED;
    }

    @Override
    public String toString()
    {
        return String.format("stream=%d v%d windowSize=%d reset=%s prio=%d %s %s", getId(), session.getVersion(),
                getWindowSize(), isReset(), priority, openState, closeState);
    }

    private boolean canSend()
    {
        OpenState openState = this.openState;
        return openState == OpenState.SYN_SENT || openState == OpenState.REPLY_RECV || openState == OpenState.REPLY_SENT;
    }

    private boolean canReceive()
    {
        OpenState openState = this.openState;
        return openState == OpenState.SYN_RECV || openState == OpenState.REPLY_RECV || openState == OpenState.REPLY_SENT;
    }

    private enum OpenState
    {
        SYN_SENT, SYN_RECV, REPLY_SENT, REPLY_RECV
    }

    private enum CloseState
    {
        OPENED, LOCALLY_CLOSED, REMOTELY_CLOSED, CLOSED
    }

    private class StreamCallback implements Callback
    {
        private final Callback callback;

        private StreamCallback()
        {
            this(Callback.Adapter.INSTANCE);
        }

        private StreamCallback(Callback callback)
        {
            this.callback = callback;
        }

        @Override
        public void succeeded()
        {
            callback.succeeded();
        }

        @Override
        public void failed(Throwable x)
        {
            close();
            callback.failed(x);
        }
    }

    private class StreamPromise implements Promise<Stream>
    {
        private final Promise<Stream> promise;

        public StreamPromise(Promise<Stream> promise)
        {
            this.promise = promise;
        }

        @Override
        public void succeeded(Stream result)
        {
            promise.succeeded(result);
        }

        @Override
        public void failed(Throwable x)
        {
            close();
            promise.failed(x);
        }
    }
}
