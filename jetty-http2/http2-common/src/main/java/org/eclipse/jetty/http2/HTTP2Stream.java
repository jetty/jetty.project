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

public class HTTP2Stream extends IdleTimeout implements IStream
{
    private static final Logger LOG = Log.getLogger(HTTP2Stream.class);

    private final AtomicReference<ConcurrentMap<String, Object>> attributes = new AtomicReference<>();
    private final AtomicReference<CloseState> closeState = new AtomicReference<>(CloseState.NOT_CLOSED);
    private final AtomicInteger sendWindow = new AtomicInteger();
    private final AtomicInteger recvWindow = new AtomicInteger();
    private final ISession session;
    private final int streamId;
    private volatile Listener listener;
    private volatile boolean reset;

    public HTTP2Stream(Scheduler scheduler, ISession session, int streamId)
    {
        super(scheduler);
        this.session = session;
        this.streamId = streamId;
    }

    @Override
    public int getId()
    {
        return streamId;
    }

    @Override
    public ISession getSession()
    {
        return session;
    }

    @Override
    public void headers(HeadersFrame frame, Callback callback)
    {
        notIdle();
        session.control(this, callback, frame, Frame.EMPTY_ARRAY);
    }

    @Override
    public void push(PushPromiseFrame frame, Promise<Stream> promise)
    {
        notIdle();
        session.push(this, promise, frame);
    }

    @Override
    public void data(DataFrame frame, Callback callback)
    {
        notIdle();
        session.data(this, callback, frame);
    }

    @Override
    public void reset(ResetFrame frame, Callback callback)
    {
        notIdle();
        session.control(this, callback, frame, Frame.EMPTY_ARRAY);
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
        return reset;
    }

    @Override
    public boolean isClosed()
    {
        return closeState.get() == CloseState.CLOSED;
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
        reset(new ResetFrame(getId(), ErrorCodes.CANCEL_STREAM_ERROR), Callback.Adapter.INSTANCE);

        // Notify the application.
        notifyFailure(this, timeout);
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
    public boolean process(Frame frame, Callback callback)
    {
        notIdle();
        switch (frame.getType())
        {
            case HEADERS:
            {
                return onHeaders((HeadersFrame)frame, callback);
            }
            case DATA:
            {
                return onData((DataFrame)frame, callback);
            }
            case RST_STREAM:
            {
                return onReset((ResetFrame)frame, callback);
            }
            case PUSH_PROMISE:
            {
                return onPush((PushPromiseFrame)frame, callback);
            }
            default:
            {
                throw new UnsupportedOperationException();
            }
        }
    }

    private boolean onHeaders(HeadersFrame frame, Callback callback)
    {
        // TODO: handle case where HEADERS after DATA.
        callback.succeeded();
        return false;
    }

    private boolean onData(DataFrame frame, Callback callback)
    {
        // TODO: handle cases where:
        // TODO: A) stream already remotely close.
        // TODO: B) DATA before HEADERS.

        if (getRecvWindow() < 0)
        {
            // It's a bad client, it does not deserve to be
            // treated gently by just resetting the stream.
            session.close(ErrorCodes.FLOW_CONTROL_ERROR, "stream_window_exceeded", callback);
            return true;
        }
        else
        {
            notifyData(this, frame, callback);
            return false;
        }
    }

    private boolean onReset(ResetFrame frame, Callback callback)
    {
        reset = true;
        callback.succeeded();
        return false;
    }

    private boolean onPush(PushPromiseFrame frame, Callback callback)
    {
        callback.succeeded();
        return false;
    }

    @Override
    public void updateClose(boolean update, boolean local)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Update close for {} close={} local={}", this, update, local);

        if (!update)
            return;

        while (true)
        {
            CloseState current = closeState.get();
            switch (current)
            {
                case NOT_CLOSED:
                {
                    CloseState newValue = local ? CloseState.LOCALLY_CLOSED : CloseState.REMOTELY_CLOSED;
                    if (closeState.compareAndSet(current, newValue))
                        return;
                    break;
                }
                case LOCALLY_CLOSED:
                {
                    if (!local)
                        close();
                    return;
                }
                case REMOTELY_CLOSED:
                {
                    if (local)
                        close();
                    return;
                }
                default:
                {
                    return;
                }
            }
        }
    }

    @Override
    public int getSendWindow()
    {
        return sendWindow.get();
    }

    protected int getRecvWindow()
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

    protected void notifyData(Stream stream, DataFrame frame, Callback callback)
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

    private void notifyFailure(Stream stream, Throwable failure)
    {
        Listener listener = this.listener;
        if (listener == null)
            return;
        try
        {
            listener.onFailure(stream, failure);
        }
        catch (Throwable x)
        {
            LOG.info("Failure while notifying listener " + listener, x);
        }
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{id=%d,sendWindow=%s,recvWindow=%s,reset=%b,%s}", getClass().getSimpleName(),
                hashCode(), getId(), sendWindow, recvWindow, reset, closeState);
    }
}
