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
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.util.Callback;

public class HTTP2Stream implements IStream
{
    private final AtomicReference<ConcurrentMap<String, Object>> attributes = new AtomicReference<>();
    private final ISession session;
    private Listener listener;

    public HTTP2Stream(ISession session)
    {
        this.session = session;
    }

    @Override
    public int getId()
    {
        return 0;
    }

    @Override
    public Session getSession()
    {
        return null;
    }

    @Override
    public void headers(HeadersFrame frame, Callback callback)
    {
        session.frame(frame, callback);
    }

    @Override
    public void data(DataFrame frame, Callback callback)
    {
        session.frame(frame, callback);
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
    public void setListener(Listener listener)
    {

    }

    @Override
    public boolean process(DataFrame frame)
    {
        return notifyData(frame);
    }

    protected boolean notifyData(DataFrame frame)
    {
        final Listener listener = this.listener;
        listener.onData(this, frame, new Callback()
        {
            @Override
            public void succeeded()
            {
                // TODO: notify flow control
            }

            @Override
            public void failed(Throwable x)
            {
                // TODO: bail out
            }
        });
        return true;
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x", getClass().getSimpleName(), hashCode());
    }
}
