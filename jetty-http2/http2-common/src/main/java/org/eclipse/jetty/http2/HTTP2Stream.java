//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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
import java.io.IOException;
import java.nio.channels.WritePendingException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PushPromiseFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.io.IdleTimeout;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Scheduler;

public class HTTP2Stream extends IdleTimeout implements IStream, Callback
{
    private static final Logger LOG = Log.getLogger(HTTP2Stream.class);

    private final AtomicReference<ConcurrentMap<String, Object>> attributes = new AtomicReference<>();
    private final AtomicReference<CloseState> closeState = new AtomicReference<>(CloseState.NOT_CLOSED);
    private final AtomicReference<Callback> writing = new AtomicReference<>();
    private final AtomicInteger sendWindow = new AtomicInteger();
    private final AtomicInteger recvWindow = new AtomicInteger();
    private final ISession session;
    private final int streamId;
    private final boolean local;
    private volatile Listener listener;
    private volatile boolean localReset;
    private volatile boolean remoteReset;

    public HTTP2Stream(Scheduler scheduler, ISession session, int streamId, boolean local)
    {
        super(scheduler);
        this.session = session;
        this.streamId = streamId;
        this.local = local;
    }

    @Override
    public int getId()
    {
        return streamId;
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
        if (!checkWrite(callback))
            return;
        notIdle();
        session.frames(this, this, frame, Frame.EMPTY_ARRAY);
    }

    @Override
    public void push(PushPromiseFrame frame, Promise<Stream> promise, Listener listener)
    {
        notIdle();
        session.push(this, promise, frame, listener);
    }

    @Override
    public void data(DataFrame frame, Callback callback)
    {
        if (!checkWrite(callback))
            return;
        notIdle();
        session.data(this, this, frame);
    }

    @Override
    public void reset(ResetFrame frame, Callback callback)
    {
        if (isReset())
            return;
        notIdle();
        localReset = true;
        session.frames(this, callback, frame, Frame.EMPTY_ARRAY);
    }

    private boolean checkWrite(Callback callback)
    {
        if (writing.compareAndSet(null, callback))
            return true;
        callback.failed(new WritePendingException());
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
        return localReset || remoteReset;
    }

    @Override
    public boolean isClosed()
    {
        return closeState.get() == CloseState.CLOSED;
    }

    public boolean isRemotelyClosed()
    {
        return closeState.get() == CloseState.REMOTELY_CLOSED;
    }

    public boolean isLocallyClosed()
    {
        return closeState.get() == CloseState.LOCALLY_CLOSED;
    }

    @Override
    public boolean isOpen()
    {
        return !isClosed();
    }

    @Override
    protected void onIdleExpired(TimeoutException timeout)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Idle timeout {}ms expired on {}", getIdleTimeout(), this);

        // The stream is now gone, we must close it to
        // avoid that its idle timeout is rescheduled.
        close();

        // Tell the other peer that we timed out.
        reset(new ResetFrame(getId(), ErrorCode.CANCEL_STREAM_ERROR.code), Callback.NOOP);

        // Notify the application.
        notifyTimeout(this, timeout);
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
            default:
            {
                throw new UnsupportedOperationException();
            }
        }
    }

    private void onHeaders(HeadersFrame frame, Callback callback)
    {
        if (updateClose(frame.isEndStream(), false))
            session.removeStream(this);
        callback.succeeded();
    }

    private void onData(DataFrame frame, Callback callback)
    {
        if (getRecvWindow() < 0)
        {
            // It's a bad client, it does not deserve to be
            // treated gently by just resetting the stream.
            session.close(ErrorCode.FLOW_CONTROL_ERROR.code, "stream_window_exceeded", Callback.NOOP);
            callback.failed(new IOException("stream_window_exceeded"));
            return;
        }

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

        if (updateClose(frame.isEndStream(), false))
            session.removeStream(this);
        notifyData(this, frame, callback);
    }

    private void onReset(ResetFrame frame, Callback callback)
    {
        remoteReset = true;
        close();
        session.removeStream(this);
        callback.succeeded();
        notifyReset(this, frame);
    }

    private void onPush(PushPromiseFrame frame, Callback callback)
    {
        // Pushed streams are implicitly locally closed.
        // They are closed when receiving an end-stream DATA frame.
        updateClose(true, true);
        callback.succeeded();
    }

    @Override
    public boolean updateClose(boolean update, boolean local)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Update close for {} close={} local={}", this, update, local);

        if (!update)
            return false;

        while (true)
        {
            CloseState current = closeState.get();
            switch (current)
            {
                case NOT_CLOSED:
                {
                    CloseState newValue = local ? CloseState.LOCALLY_CLOSED : CloseState.REMOTELY_CLOSED;
                    if (closeState.compareAndSet(current, newValue))
                        return false;
                    break;
                }
                case LOCALLY_CLOSED:
                {
                    if (local)
                        return false;
                    close();
                    return true;
                }
                case REMOTELY_CLOSED:
                {
                    if (!local)
                        return false;
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
        closeState.set(CloseState.CLOSED);
        onClose();
    }

    @Override
    public void succeeded()
    {
        Callback callback = writing.getAndSet(null);
        callback.succeeded();
    }

    @Override
    public void failed(Throwable x)
    {
        Callback callback = writing.getAndSet(null);
        callback.failed(x);
    }

    private void notifyData(Stream stream, DataFrame frame, Callback callback)
    {
        final Listener listener = this.listener;
        if (listener == null)
            return;
        try
        {
            listener.onData(stream, frame, callback);
        }
        catch (Throwable x)
        {
            LOG.info("Failure while notifying listener " + listener, x);
        }
    }

    private void notifyReset(Stream stream, ResetFrame frame)
    {
        final Listener listener = this.listener;
        if (listener == null)
            return;
        try
        {
            listener.onReset(stream, frame);
        }
        catch (Throwable x)
        {
            LOG.info("Failure while notifying listener " + listener, x);
        }
    }

    private void notifyTimeout(Stream stream, Throwable failure)
    {
        Listener listener = this.listener;
        if (listener == null)
            return;
        try
        {
            listener.onTimeout(stream, failure);
        }
        catch (Throwable x)
        {
            LOG.info("Failure while notifying listener " + listener, x);
        }
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x#%d{sendWindow=%s,recvWindow=%s,reset=%b,%s}", getClass().getSimpleName(),
                hashCode(), getId(), sendWindow, recvWindow, isReset(), closeState);
    }
}
