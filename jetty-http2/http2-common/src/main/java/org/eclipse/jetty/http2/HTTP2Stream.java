//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

import java.io.EOFException;
import java.io.IOException;
import java.nio.channels.WritePendingException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.FailureFrame;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PushPromiseFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.frames.WindowUpdateFrame;
import org.eclipse.jetty.io.CyclicTimeouts;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.MathUtils;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HTTP2Stream implements IStream, Callback, Dumpable, CyclicTimeouts.Expirable
{
    private static final Logger LOG = LoggerFactory.getLogger(HTTP2Stream.class);

    private final AutoLock lock = new AutoLock();
    private Deque<DataEntry> dataQueue;
    private final AtomicReference<Object> attachment = new AtomicReference<>();
    private final AtomicReference<ConcurrentMap<String, Object>> attributes = new AtomicReference<>();
    private final AtomicReference<CloseState> closeState = new AtomicReference<>(CloseState.NOT_CLOSED);
    private final AtomicInteger sendWindow = new AtomicInteger();
    private final AtomicInteger recvWindow = new AtomicInteger();
    private final long creationNanoTime = NanoTime.now();
    private final ISession session;
    private final int streamId;
    private final MetaData.Request request;
    private final boolean local;
    private Callback sendCallback;
    private Throwable failure;
    private boolean localReset;
    private boolean remoteReset;
    private Listener listener;
    private long dataLength;
    private long dataDemand;
    private boolean dataInitial;
    private boolean dataProcess;
    private boolean committed;
    private long idleTimeout;
    private long expireNanoTime = Long.MAX_VALUE;

    public HTTP2Stream(ISession session, int streamId, MetaData.Request request, boolean local)
    {
        this.session = session;
        this.streamId = streamId;
        this.request = request;
        this.local = local;
        this.dataLength = Long.MIN_VALUE;
        this.dataInitial = true;
    }

    @Deprecated
    public HTTP2Stream(Scheduler scheduler, ISession session, int streamId, MetaData.Request request, boolean local)
    {
        this(session, streamId, request, local);
    }

    @Override
    public int getId()
    {
        return streamId;
    }

    @Override
    public Object getAttachment()
    {
        return attachment.get();
    }

    @Override
    public void setAttachment(Object attachment)
    {
        this.attachment.set(attachment);
    }

    @Override
    public boolean isLocal()
    {
        return local;
    }

    @Override
    public ISession getSession()
    {
        return session;
    }

    @Override
    public void headers(HeadersFrame frame, Callback callback)
    {
        send(new FrameList(frame), callback);
    }

    @Override
    public void send(FrameList frameList, Callback callback)
    {
        if (startWrite(callback))
            session.frames(this, frameList.getFrames(), this);
    }

    @Override
    public void push(PushPromiseFrame frame, Promise<Stream> promise, Listener listener)
    {
        session.push(this, promise, frame, listener);
    }

    @Override
    public void data(DataFrame frame, Callback callback)
    {
        if (startWrite(callback))
            session.data(this, this, frame);
    }

    @Override
    public void reset(ResetFrame frame, Callback callback)
    {
        Throwable resetFailure = null;
        try (AutoLock l = lock.lock())
        {
            if (isReset())
            {
                resetFailure = failure;
            }
            else
            {
                localReset = true;
                failure = new EOFException("reset");
            }
        }
        if (resetFailure != null)
            callback.failed(resetFailure);
        else
            ((HTTP2Session)session).reset(this, frame, callback);
    }

    private boolean startWrite(Callback callback)
    {
        Throwable failure;
        try (AutoLock l = lock.lock())
        {
            failure = this.failure;
            if (failure == null && sendCallback == null)
            {
                sendCallback = callback;
                return true;
            }
        }
        if (failure == null)
            failure = new WritePendingException();
        callback.failed(failure);
        return false;
    }

    @Override
    public Object getAttribute(String key)
    {
        return attributes().get(key);
    }

    @Override
    public void setAttribute(String key, Object value)
    {
        attributes().put(key, value);
    }

    @Override
    public Object removeAttribute(String key)
    {
        return attributes().remove(key);
    }

    @Override
    public boolean isReset()
    {
        try (AutoLock l = lock.lock())
        {
            return localReset || remoteReset;
        }
    }

    private boolean isFailed()
    {
        try (AutoLock l = lock.lock())
        {
            return failure != null;
        }
    }

    @Override
    public boolean isResetOrFailed()
    {
        try (AutoLock l = lock.lock())
        {
            return isReset() || isFailed();
        }
    }

    @Override
    public boolean isClosed()
    {
        return closeState.get() == CloseState.CLOSED;
    }

    @Override
    public boolean isRemotelyClosed()
    {
        CloseState state = closeState.get();
        return state == CloseState.REMOTELY_CLOSED || state == CloseState.CLOSING || state == CloseState.CLOSED;
    }

    @Override
    public boolean failAllData(Throwable x)
    {
        Deque<DataEntry> copy;
        try (AutoLock l = lock.lock())
        {
            dataDemand = 0;
            copy = dataQueue;
            dataQueue = null;
        }
        DataEntry lastDataEntry = null;
        if (copy != null)
        {
            copy.forEach(dataEntry -> dataEntry.callback.failed(x));
            lastDataEntry = copy.isEmpty() ? null : copy.peekLast();
        }
        if (lastDataEntry == null)
            return isRemotelyClosed();
        return lastDataEntry.frame.isEndStream();
    }

    public boolean isLocallyClosed()
    {
        return closeState.get() == CloseState.LOCALLY_CLOSED;
    }

    @Override
    public void commit()
    {
        committed = true;
    }

    @Override
    public boolean isCommitted()
    {
        return committed;
    }

    public boolean isOpen()
    {
        return !isClosed();
    }

    @Override
    public void notIdle()
    {
        long idleTimeout = getIdleTimeout();
        if (idleTimeout > 0)
            expireNanoTime = NanoTime.now() + TimeUnit.MILLISECONDS.toNanos(idleTimeout);
    }

    @Override
    public long getExpireNanoTime()
    {
        return expireNanoTime;
    }

    @Override
    public long getIdleTimeout()
    {
        return idleTimeout;
    }

    @Override
    public void setIdleTimeout(long idleTimeout)
    {
        this.idleTimeout = idleTimeout;
        notIdle();
        ((HTTP2Session)session).scheduleTimeout(this);
    }

    protected void onIdleExpired(TimeoutException timeout)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Idle timeout {}ms expired on {}", getIdleTimeout(), this);

        // Notify the application.
        if (notifyIdleTimeout(this, timeout))
        {
            // Tell the other peer that we timed out.
            reset(new ResetFrame(getId(), ErrorCode.CANCEL_STREAM_ERROR.code), Callback.NOOP);
        }
    }

    private ConcurrentMap<String, Object> attributes()
    {
        ConcurrentMap<String, Object> map = attributes.get();
        if (map == null)
        {
            map = new ConcurrentHashMap<>();
            if (!attributes.compareAndSet(null, map))
            {
                map = attributes.get();
            }
        }
        return map;
    }

    @Override
    public Listener getListener()
    {
        return listener;
    }

    @Override
    public void setListener(Listener listener)
    {
        this.listener = listener;
    }

    @Override
    public void process(Frame frame, Callback callback)
    {
        notIdle();
        switch (frame.getType())
        {
            case PREFACE:
            {
                onNewStream(callback);
                break;
            }
            case HEADERS:
            {
                onHeaders((HeadersFrame)frame, callback);
                break;
            }
            case DATA:
            {
                onData((DataFrame)frame, callback);
                break;
            }
            case RST_STREAM:
            {
                onReset((ResetFrame)frame, callback);
                break;
            }
            case PUSH_PROMISE:
            {
                onPush((PushPromiseFrame)frame, callback);
                break;
            }
            case WINDOW_UPDATE:
            {
                onWindowUpdate((WindowUpdateFrame)frame, callback);
                break;
            }
            case FAILURE:
            {
                onFailure((FailureFrame)frame, callback);
                break;
            }
            default:
            {
                throw new UnsupportedOperationException();
            }
        }
    }

    private void onNewStream(Callback callback)
    {
        notifyNewStream(this);
        callback.succeeded();
    }

    private void onHeaders(HeadersFrame frame, Callback callback)
    {
        MetaData metaData = frame.getMetaData();
        if (metaData.isRequest() || metaData.isResponse())
        {
            HttpFields fields = metaData.getFields();
            long length = -1;
            if (fields != null && !HttpMethod.CONNECT.is(request.getMethod()))
                length = fields.getLongField(HttpHeader.CONTENT_LENGTH);
            dataLength = length >= 0 ? length : Long.MIN_VALUE;
        }
        callback.succeeded();
    }

    private void onData(DataFrame frame, Callback callback)
    {
        // SPEC: remotely closed streams must be replied with a reset.
        if (isRemotelyClosed())
        {
            reset(new ResetFrame(streamId, ErrorCode.STREAM_CLOSED_ERROR.code), Callback.NOOP);
            callback.failed(new EOFException("stream_closed"));
            return;
        }

        if (isReset())
        {
            // Just drop the frame.
            callback.failed(new IOException("stream_reset"));
            return;
        }

        if (dataLength != Long.MIN_VALUE)
        {
            dataLength -= frame.remaining();
            if (dataLength < 0 || (frame.isEndStream() && dataLength != 0))
            {
                reset(new ResetFrame(streamId, ErrorCode.PROTOCOL_ERROR.code), Callback.NOOP);
                callback.failed(new IOException("invalid_data_length"));
                return;
            }
        }

        boolean initial;
        boolean proceed = false;
        DataEntry entry = new DataEntry(frame, callback);
        try (AutoLock l = lock.lock())
        {
            if (dataQueue == null)
                dataQueue = new ArrayDeque<>();
            dataQueue.offer(entry);
            initial = dataInitial;
            if (initial)
            {
                dataInitial = false;
                // Fake that we are processing data so we return
                // from onBeforeData() before calling onData().
                dataProcess = true;
            }
            else if (!dataProcess)
            {
                dataProcess = proceed = dataDemand > 0;
            }
        }
        if (initial)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Starting data processing of {} for {}", frame, this);
            notifyBeforeData(this);
            try (AutoLock l = lock.lock())
            {
                dataProcess = proceed = dataDemand > 0;
            }
        }
        if (LOG.isDebugEnabled())
            LOG.debug("{} data processing of {} for {}", proceed ? "Proceeding" : "Stalling", frame, this);
        if (proceed)
            processData();
    }

    @Override
    public void demand(long n)
    {
        if (n <= 0)
            throw new IllegalArgumentException("Invalid demand " + n);
        long demand;
        boolean proceed = false;
        try (AutoLock l = lock.lock())
        {
            demand = dataDemand = MathUtils.cappedAdd(dataDemand, n);
            if (!dataProcess)
                dataProcess = proceed = dataQueue != null && !dataQueue.isEmpty();
        }
        if (LOG.isDebugEnabled())
            LOG.debug("Demand {}/{}, {} data processing for {}", n, demand, proceed ? "proceeding" : "stalling", this);
        if (proceed)
            processData();
    }

    private void processData()
    {
        while (true)
        {
            DataEntry dataEntry;
            try (AutoLock l = lock.lock())
            {
                if (dataQueue == null || dataQueue.isEmpty() || dataDemand == 0)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Stalling data processing for {}", this);
                    dataProcess = false;
                    return;
                }
                --dataDemand;
                dataEntry = dataQueue.poll();
            }
            DataFrame frame = dataEntry.frame;
            if (updateClose(frame.isEndStream(), CloseState.Event.RECEIVED))
                session.removeStream(this);
            notifyDataDemanded(this, frame, dataEntry.callback);
        }
    }

    private long demand()
    {
        try (AutoLock l = lock.lock())
        {
            return dataDemand;
        }
    }

    private void onReset(ResetFrame frame, Callback callback)
    {
        try (AutoLock l = lock.lock())
        {
            remoteReset = true;
            failure = new EofException("reset");
        }
        close();
        if (session.removeStream(this))
            notifyReset(this, frame, callback);
        else
            callback.succeeded();
    }

    private void onPush(PushPromiseFrame frame, Callback callback)
    {
        // Pushed streams are implicitly locally closed.
        // They are closed when receiving an end-stream DATA frame.
        updateClose(true, CloseState.Event.AFTER_SEND);
        callback.succeeded();
    }

    private void onWindowUpdate(WindowUpdateFrame frame, Callback callback)
    {
        callback.succeeded();
    }

    private void onFailure(FailureFrame frame, Callback callback)
    {
        try (AutoLock l = lock.lock())
        {
            failure = frame.getFailure();
        }
        close();
        if (session.removeStream(this))
            notifyFailure(this, frame, callback);
        else
            callback.succeeded();
    }

    @Override
    public boolean updateClose(boolean update, CloseState.Event event)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Update close for {} update={} event={}", this, update, event);

        if (!update)
            return false;

        switch (event)
        {
            case RECEIVED:
                return updateCloseAfterReceived();
            case BEFORE_SEND:
                return updateCloseBeforeSend();
            case AFTER_SEND:
                return updateCloseAfterSend();
            default:
                return false;
        }
    }

    private boolean updateCloseAfterReceived()
    {
        while (true)
        {
            CloseState current = closeState.get();
            switch (current)
            {
                case NOT_CLOSED:
                {
                    if (closeState.compareAndSet(current, CloseState.REMOTELY_CLOSED))
                        return false;
                    break;
                }
                case LOCALLY_CLOSING:
                {
                    if (closeState.compareAndSet(current, CloseState.CLOSING))
                    {
                        updateStreamCount(0, 1);
                        return false;
                    }
                    break;
                }
                case LOCALLY_CLOSED:
                {
                    close();
                    return true;
                }
                default:
                {
                    return false;
                }
            }
        }
    }

    private boolean updateCloseBeforeSend()
    {
        while (true)
        {
            CloseState current = closeState.get();
            switch (current)
            {
                case NOT_CLOSED:
                {
                    if (closeState.compareAndSet(current, CloseState.LOCALLY_CLOSING))
                        return false;
                    break;
                }
                case REMOTELY_CLOSED:
                {
                    if (closeState.compareAndSet(current, CloseState.CLOSING))
                    {
                        updateStreamCount(0, 1);
                        return false;
                    }
                    break;
                }
                default:
                {
                    return false;
                }
            }
        }
    }

    private boolean updateCloseAfterSend()
    {
        while (true)
        {
            CloseState current = closeState.get();
            switch (current)
            {
                case NOT_CLOSED:
                case LOCALLY_CLOSING:
                {
                    if (closeState.compareAndSet(current, CloseState.LOCALLY_CLOSED))
                        return false;
                    break;
                }
                case REMOTELY_CLOSED:
                case CLOSING:
                {
                    close();
                    return true;
                }
                default:
                {
                    return false;
                }
            }
        }
    }

    public int getSendWindow()
    {
        return sendWindow.get();
    }

    public int getRecvWindow()
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
    public void close()
    {
        CloseState oldState = closeState.getAndSet(CloseState.CLOSED);
        if (oldState != CloseState.CLOSED)
        {
            int deltaClosing = oldState == CloseState.CLOSING ? -1 : 0;
            updateStreamCount(-1, deltaClosing);
            onClose();
        }
    }

    public void onClose()
    {
        notifyClosed(this);
    }

    private void updateStreamCount(int deltaStream, int deltaClosing)
    {
        ((HTTP2Session)session).updateStreamCount(isLocal(), deltaStream, deltaClosing);
    }

    @Override
    public void succeeded()
    {
        Callback callback = endWrite();
        if (callback != null)
            callback.succeeded();
    }

    @Override
    public void failed(Throwable x)
    {
        Callback callback = endWrite();
        if (callback != null)
            callback.failed(x);
    }

    @Override
    public InvocationType getInvocationType()
    {
        synchronized (this)
        {
            return sendCallback != null ? sendCallback.getInvocationType() : Callback.super.getInvocationType();
        }
    }

    private Callback endWrite()
    {
        try (AutoLock l = lock.lock())
        {
            Callback callback = sendCallback;
            sendCallback = null;
            return callback;
        }
    }

    private void notifyNewStream(Stream stream)
    {
        Listener listener = this.listener;
        if (listener != null)
        {
            try
            {
                listener.onNewStream(stream);
            }
            catch (Throwable x)
            {
                LOG.info("Failure while notifying listener {}", listener, x);
            }
        }
    }

    private void notifyBeforeData(Stream stream)
    {
        Listener listener = this.listener;
        if (listener != null)
        {
            try
            {
                listener.onBeforeData(stream);
            }
            catch (Throwable x)
            {
                LOG.info("Failure while notifying listener {}", listener, x);
            }
        }
        else
        {
            stream.demand(1);
        }
    }

    private void notifyDataDemanded(Stream stream, DataFrame frame, Callback callback)
    {
        Listener listener = this.listener;
        if (listener != null)
        {
            try
            {
                listener.onDataDemanded(stream, frame, callback);
            }
            catch (Throwable x)
            {
                LOG.info("Failure while notifying listener {}", listener, x);
                callback.failed(x);
            }
        }
        else
        {
            callback.succeeded();
            stream.demand(1);
        }
    }

    private void notifyReset(Stream stream, ResetFrame frame, Callback callback)
    {
        Listener listener = this.listener;
        if (listener != null)
        {
            try
            {
                listener.onReset(stream, frame, callback);
            }
            catch (Throwable x)
            {
                LOG.info("Failure while notifying listener {}", listener, x);
                callback.failed(x);
            }
        }
        else
        {
            callback.succeeded();
        }
    }

    private boolean notifyIdleTimeout(Stream stream, Throwable failure)
    {
        Listener listener = this.listener;
        if (listener == null)
            return true;
        try
        {
            return listener.onIdleTimeout(stream, failure);
        }
        catch (Throwable x)
        {
            LOG.info("Failure while notifying listener {}", listener, x);
            return true;
        }
    }

    private void notifyFailure(Stream stream, FailureFrame frame, Callback callback)
    {
        Listener listener = this.listener;
        if (listener != null)
        {
            try
            {
                listener.onFailure(stream, frame.getError(), frame.getReason(), frame.getFailure(), callback);
            }
            catch (Throwable x)
            {
                LOG.info("Failure while notifying listener {}", listener, x);
                callback.failed(x);
            }
        }
        else
        {
            callback.succeeded();
        }
    }

    private void notifyClosed(Stream stream)
    {
        Listener listener = this.listener;
        if (listener == null)
            return;
        try
        {
            listener.onClosed(stream);
        }
        catch (Throwable x)
        {
            LOG.info("Failure while notifying listener {}", listener, x);
        }
    }

    @Override
    public String dump()
    {
        return Dumpable.dump(this);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        out.append(toString()).append(System.lineSeparator());
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x#%d@%x{sendWindow=%s,recvWindow=%s,demand=%d,reset=%b/%b,%s,age=%d,attachment=%s}",
            getClass().getSimpleName(),
            hashCode(),
            getId(),
            session.hashCode(),
            sendWindow,
            recvWindow,
            demand(),
            localReset,
            remoteReset,
            closeState,
            NanoTime.millisSince(creationNanoTime),
            attachment);
    }

    private static class DataEntry
    {
        private final DataFrame frame;
        private final Callback callback;

        private DataEntry(DataFrame frame, Callback callback)
        {
            this.frame = frame;
            this.callback = callback;
        }
    }
}
