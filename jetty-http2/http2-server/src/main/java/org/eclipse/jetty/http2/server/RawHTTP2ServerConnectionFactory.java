//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http2.server;

import java.util.Map;
import java.util.Objects;

import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.GoAwayFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PingFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;

public class RawHTTP2ServerConnectionFactory extends AbstractHTTP2ServerConnectionFactory
{
    private final ServerSessionListener listener;

    public RawHTTP2ServerConnectionFactory(ServerSessionListener listener)
    {
        this(new HttpConfiguration(), listener);
    }

    public RawHTTP2ServerConnectionFactory(HttpConfiguration httpConfiguration, ServerSessionListener listener)
    {
        super(httpConfiguration);
        this.listener = new RawServerSessionListener(Objects.requireNonNull(listener));
    }

    public RawHTTP2ServerConnectionFactory(HttpConfiguration httpConfiguration, ServerSessionListener listener, String... protocols)
    {
        super(httpConfiguration, protocols);
        this.listener = listener;
    }

    @Override
    protected ServerSessionListener newSessionListener(Connector connector, EndPoint endPoint)
    {
        return listener;
    }

    private class RawServerSessionListener implements ServerSessionListener
    {
        private final ServerSessionListener delegate;

        private RawServerSessionListener(ServerSessionListener delegate)
        {
            this.delegate = delegate;
        }

        @Override
        public void onAccept(Session session)
        {
            delegate.onAccept(session);
        }

        @Override
        public Map<Integer, Integer> onPreface(Session session)
        {
            Map<Integer, Integer> settings = newSettings();
            Map<Integer, Integer> moreSettings = delegate.onPreface(session);
            if (moreSettings != null)
                settings.putAll(moreSettings);
            return settings;
        }

        @Override
        public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
        {
            return delegate.onNewStream(stream, frame);
        }

        @Override
        public void onSettings(Session session, SettingsFrame frame)
        {
            delegate.onSettings(session, frame);
        }

        @Override
        public void onPing(Session session, PingFrame frame)
        {
            delegate.onPing(session, frame);
        }

        @Override
        public void onReset(Session session, ResetFrame frame)
        {
            delegate.onReset(session, frame);
        }

        @Override
        public void onGoAway(Session session, GoAwayFrame frame)
        {
            delegate.onGoAway(session, frame);
        }

        @Override
        public void onClose(Session session, GoAwayFrame frame)
        {
            delegate.onClose(session, frame);
        }

        @Override
        public boolean onIdleTimeout(Session session)
        {
            return delegate.onIdleTimeout(session);
        }

        @Override
        public void onFailure(Session session, Throwable failure)
        {
            delegate.onFailure(session, failure);
        }
    }
}
