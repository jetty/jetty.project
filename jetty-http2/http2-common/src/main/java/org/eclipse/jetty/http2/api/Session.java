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

import java.util.Collection;
import java.util.Map;

import org.eclipse.jetty.http2.frames.GoAwayFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PingFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;

public interface Session
{
    public void newStream(HeadersFrame frame, Promise<Stream> promise, Stream.Listener listener);

    public void settings(SettingsFrame frame, Callback callback);

    public void ping(PingFrame frame, Callback callback);

    public void close(int error, String payload, Callback callback);

    public boolean isClosed();

    public Collection<Stream> getStreams();

    public Stream getStream(int streamId);

    // TODO: remote and local address, etc. see SPDY's Session

    public interface Listener
    {
        public Map<Integer,Integer> onPreface(Session session);

        public Stream.Listener onNewStream(Stream stream, HeadersFrame frame);

        public void onSettings(Session session, SettingsFrame frame);

        public void onPing(Session session, PingFrame frame);

        public void onReset(Session session, ResetFrame frame);

        public void onClose(Session session, GoAwayFrame frame);

        // TODO: how come this is not called ???
        public void onFailure(Session session, Throwable failure);

        public static class Adapter implements Session.Listener
        {
            @Override
            public Map<Integer, Integer> onPreface(Session session)
            {
                return null;
            }

            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                return null;
            }

            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
            }

            @Override
            public void onPing(Session session, PingFrame frame)
            {
            }

            @Override
            public void onReset(Session session, ResetFrame frame)
            {
            }

            @Override
            public void onClose(Session session, GoAwayFrame frame)
            {
            }

            @Override
            public void onFailure(Session session, Throwable failure)
            {
            }
        }
    }
}
