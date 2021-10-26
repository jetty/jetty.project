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

package org.eclipse.jetty.http3.server;

import java.util.Objects;

import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.internal.HTTP3Stream;
import org.eclipse.jetty.http3.server.internal.HttpChannelOverHTTP3;
import org.eclipse.jetty.http3.server.internal.ServerHTTP3Session;
import org.eclipse.jetty.http3.server.internal.ServerHTTP3StreamConnection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.HttpConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HTTP3ServerConnectionFactory extends AbstractHTTP3ServerConnectionFactory
{
    public HTTP3ServerConnectionFactory()
    {
        this(new HttpConfiguration());
    }

    public HTTP3ServerConnectionFactory(HttpConfiguration configuration)
    {
        super(configuration, new HTTP3SessionListener());
    }

    private static class HTTP3SessionListener implements Session.Server.Listener
    {
        private static final Logger LOG = LoggerFactory.getLogger(HTTP3SessionListener.class);

        @Override
        public Stream.Listener onRequest(Stream stream, HeadersFrame frame)
        {
            HTTP3Stream http3Stream = (HTTP3Stream)stream;
            HTTP3StreamListener listener = new HTTP3StreamListener(http3Stream.getEndPoint());
            listener.onRequest(stream, frame);
            return listener;
        }

        @Override
        public boolean onIdleTimeout(Session session)
        {
            boolean result = session.getStreams().stream()
                .map(stream -> (HTTP3Stream)stream)
                .map(stream -> (HttpChannelOverHTTP3)stream.getAttachment())
                .filter(Objects::nonNull)
                .map(channel -> channel.getState().isIdle())
                .reduce(true, Boolean::logicalAnd);
            if (LOG.isDebugEnabled())
                LOG.debug("{} idle timeout on {}", result ? "confirmed" : "ignored", session);
            return result;
        }

        @Override
        public void onFailure(Session session, Throwable failure)
        {
            // TODO
            throw new UnsupportedOperationException();
        }
    }

    private static class HTTP3StreamListener implements Stream.Listener
    {
        private final EndPoint endPoint;

        public HTTP3StreamListener(EndPoint endPoint)
        {
            this.endPoint = endPoint;
        }

        private ServerHTTP3StreamConnection getConnection()
        {
            return (ServerHTTP3StreamConnection)endPoint.getConnection();
        }

        public void onRequest(Stream stream, HeadersFrame frame)
        {
            HTTP3Stream http3Stream = (HTTP3Stream)stream;
            Runnable runnable = getConnection().onRequest(http3Stream, frame);
            if (runnable != null)
            {
                ServerHTTP3Session protocolSession = (ServerHTTP3Session)http3Stream.getSession().getProtocolSession();
                protocolSession.offer(runnable);
            }
        }

        @Override
        public void onDataAvailable(Stream stream)
        {
            HTTP3Stream http3Stream = (HTTP3Stream)stream;
            Runnable runnable = getConnection().onDataAvailable(http3Stream);
            if (runnable != null)
            {
                ServerHTTP3Session protocolSession = (ServerHTTP3Session)http3Stream.getSession().getProtocolSession();
                protocolSession.offer(runnable);
            }
        }

        @Override
        public void onTrailer(Stream stream, HeadersFrame frame)
        {
            HTTP3Stream http3Stream = (HTTP3Stream)stream;
            Runnable runnable = getConnection().onTrailer(http3Stream, frame);
            if (runnable != null)
            {
                ServerHTTP3Session protocolSession = (ServerHTTP3Session)http3Stream.getSession().getProtocolSession();
                protocolSession.offer(runnable);
            }
        }

        @Override
        public boolean onIdleTimeout(Stream stream, Throwable failure)
        {
            return getConnection().onIdleTimeout((HTTP3Stream)stream, failure);
        }

        @Override
        public void onFailure(Stream stream, Throwable failure)
        {
            getConnection().onFailure((HTTP3Stream)stream, failure);
        }
    }
}
