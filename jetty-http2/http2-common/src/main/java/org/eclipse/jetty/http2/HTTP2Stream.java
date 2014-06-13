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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class HTTP2Stream implements IStream
{
    private static final Logger LOG = Log.getLogger(HTTP2Stream.class);

    private final AtomicReference<ConcurrentMap<String, Object>> attributes = new AtomicReference<>();
    private final AtomicReference<CloseState> closeState = new AtomicReference<>(CloseState.NOT_CLOSED);
    private final AtomicInteger windowSize = new AtomicInteger();
    private final ISession session;
    private final HeadersFrame frame;
    private Listener listener;
    private volatile boolean reset = false;

    public HTTP2Stream(ISession session, HeadersFrame frame)
    {
        this.session = session;
        this.frame = frame;
    }

    @Override
    public int getId()
    {
        return frame.getStreamId();
    }

    @Override
    public ISession getSession()
    {
        return session;
    }

    @Override
    public void headers(HeadersFrame frame, Callback callback)
    {
        session.frame(this, frame, callback);
    }

    @Override
    public void data(DataFrame frame, Callback callback)
    {
        session.frame(this, frame, callback);
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
        switch (frame.getType())
        {
            case DATA:
            {
                return notifyData((DataFrame)frame, callback);
            }
            case HEADERS:
            {
                return false;
            }
            case RST_STREAM:
            {
                reset = true;
                return false;
            }
            default:
            {
                throw new UnsupportedOperationException();
            }
        }
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
                    if (local)
                        return;
                    if (closeState.compareAndSet(current, CloseState.CLOSED))
                        return;
                    break;
                }
                case REMOTELY_CLOSED:
                {
                    if (!local)
                        return;
                    if (closeState.compareAndSet(current, CloseState.CLOSED))
                        return;
                    break;
                }
                default:
                {
                    return;
                }
            }
        }
    }

    @Override
    public int getWindowSize()
    {
        return windowSize.get();
    }

    @Override
    public int updateWindowSize(int delta)
    {
        return windowSize.getAndAdd(delta);
    }

    protected boolean notifyData(DataFrame frame, Callback callback)
    {
        final Listener listener = this.listener;
        if (listener == null)
            return false;
        try
        {
            listener.onData(this, frame, callback);
            return false;
        }
        catch (Throwable x)
        {
            LOG.info("Failure while notifying listener " + listener, x);
            return false;
        }
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{id=%d,windowSize=%s,%s}", getClass().getSimpleName(),
                hashCode(), getId(), windowSize, closeState);
    }

    private enum CloseState
    {
        NOT_CLOSED, LOCALLY_CLOSED, REMOTELY_CLOSED, CLOSED
    }
}
