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

package org.eclipse.jetty.http2.api;

import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PushPromiseFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;

public interface Stream
{
    public int getId();

    public Session getSession();

    public void headers(HeadersFrame frame, Callback callback);

    public void push(PushPromiseFrame frame, Promise<Stream> promise);

    public void data(DataFrame frame, Callback callback);

    public void reset(ResetFrame frame, Callback callback);

    public Object getAttribute(String key);

    public void setAttribute(String key, Object value);

    public Object removeAttribute(String key);

    public boolean isReset();

    public boolean isClosed();

    public long getIdleTimeout();

    public void setIdleTimeout(long idleTimeout);

    // TODO: see SPDY's Stream

    public interface Listener
    {
        public void onHeaders(Stream stream, HeadersFrame frame);

        public Listener onPush(Stream stream, PushPromiseFrame frame);

        public void onData(Stream stream, DataFrame frame, Callback callback);

        public void onReset(Stream stream, ResetFrame frame);

        public void onTimeout(Stream stream, Throwable x);

        public static class Adapter implements Listener
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
            }

            @Override
            public Listener onPush(Stream stream, PushPromiseFrame frame)
            {
                return null;
            }

            @Override
            public void onData(Stream stream, DataFrame frame, Callback callback)
            {
                callback.succeeded();
            }

            @Override
            public void onReset(Stream stream, ResetFrame frame)
            {
            }

            @Override
            public void onTimeout(Stream stream, Throwable x)
            {
            }
        }
    }
}
