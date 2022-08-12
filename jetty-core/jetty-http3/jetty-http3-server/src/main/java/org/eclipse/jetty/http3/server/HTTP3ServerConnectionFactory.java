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

package org.eclipse.jetty.http3.server;

import java.util.Objects;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.internal.HTTP3Stream;
import org.eclipse.jetty.http3.server.internal.HTTP3StreamServer;
import org.eclipse.jetty.http3.server.internal.HttpStreamOverHTTP3;
import org.eclipse.jetty.http3.server.internal.ServerHTTP3Session;
import org.eclipse.jetty.http3.server.internal.ServerHTTP3StreamConnection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.ConnectionMetaData;
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
        configuration.addCustomizer((request, responseHeaders) ->
        {
            ConnectionMetaData connectionMetaData = request.getConnectionMetaData();
            HTTP3ServerConnector http3Connector = connectionMetaData.getConnector().getServer().getBean(HTTP3ServerConnector.class);
            if (http3Connector != null && HttpVersion.HTTP_2 == connectionMetaData.getHttpVersion())
            {
                HttpField altSvc = http3Connector.getAltSvcHttpField();
                if (altSvc != null)
                    responseHeaders.add(altSvc);
            }
            return request;
        });
    }

    private static class HTTP3SessionListener implements Session.Server.Listener
    {
        private static final Logger LOG = LoggerFactory.getLogger(HTTP3SessionListener.class);

        @Override
        public Stream.Server.Listener onRequest(Stream.Server stream, HeadersFrame frame)
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
                .map(stream -> (HttpStreamOverHTTP3)stream.getAttachment())
                .filter(Objects::nonNull)
                .map(HttpStreamOverHTTP3::isIdle)
                .reduce(true, Boolean::logicalAnd);
            if (LOG.isDebugEnabled())
                LOG.debug("{} idle timeout on {}", result ? "confirmed" : "ignored", session);
            return result;
        }

        @Override
        public void onFailure(Session session, long error, String reason, Throwable failure)
        {
            session.getStreams().stream()
                .map(stream -> (HTTP3Stream)stream)
                .forEach(stream -> stream.onFailure(error, failure));
        }
    }

    private static class HTTP3StreamListener implements Stream.Server.Listener
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
            HTTP3StreamServer http3Stream = (HTTP3StreamServer)stream;
            Runnable task = getConnection().onRequest(http3Stream, frame);
            if (task != null)
            {
                ServerHTTP3Session protocolSession = (ServerHTTP3Session)http3Stream.getSession().getProtocolSession();
                protocolSession.offer(task, false);
            }
        }

        @Override
        public void onDataAvailable(Stream.Server stream)
        {
            HTTP3Stream http3Stream = (HTTP3Stream)stream;
            Runnable task = getConnection().onDataAvailable(http3Stream);
            if (task != null)
            {
                ServerHTTP3Session protocolSession = (ServerHTTP3Session)http3Stream.getSession().getProtocolSession();
                protocolSession.offer(task, false);
            }
        }

        @Override
        public void onTrailer(Stream.Server stream, HeadersFrame frame)
        {
            HTTP3Stream http3Stream = (HTTP3Stream)stream;
            Runnable task = getConnection().onTrailer(http3Stream, frame);
            if (task != null)
            {
                ServerHTTP3Session protocolSession = (ServerHTTP3Session)http3Stream.getSession().getProtocolSession();
                protocolSession.offer(task, false);
            }
        }

        @Override
        public boolean onIdleTimeout(Stream.Server stream, Throwable failure)
        {
            HTTP3Stream http3Stream = (HTTP3Stream)stream;
            return getConnection().onIdleTimeout((HTTP3Stream)stream, failure, task ->
            {
                if (task != null)
                {
                    ServerHTTP3Session protocolSession = (ServerHTTP3Session)http3Stream.getSession().getProtocolSession();
                    protocolSession.offer(task, true);
                }
            });
        }

        @Override
        public void onFailure(Stream.Server stream, long error, Throwable failure)
        {
            HTTP3Stream http3Stream = (HTTP3Stream)stream;
            Runnable task = getConnection().onFailure((HTTP3Stream)stream, failure);
            if (task != null)
            {
                ServerHTTP3Session protocolSession = (ServerHTTP3Session)http3Stream.getSession().getProtocolSession();
                protocolSession.offer(task, true);
            }
        }
    }
}
