//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http3.HTTP3Stream;
import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.server.internal.HTTP3SessionServer;
import org.eclipse.jetty.http3.server.internal.HTTP3StreamServer;
import org.eclipse.jetty.http3.server.internal.HttpStreamOverHTTP3;
import org.eclipse.jetty.http3.server.internal.ServerHTTP3StreamConnection;
import org.eclipse.jetty.server.ConnectionMetaData;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.thread.Invocable;
import org.eclipse.jetty.util.thread.ThreadPool;
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
        configuration.addCustomizer(new AltSvcCustomizer());
    }

    private class AltSvcCustomizer implements HttpConfiguration.Customizer
    {
        @Override
        public Request customize(Request request, HttpFields.Mutable responseHeaders)
        {
            ConnectionMetaData connectionMetaData = request.getConnectionMetaData();
            if (HttpVersion.HTTP_2 == connectionMetaData.getHttpVersion())
            {
                Connector h3Connector = HTTP3ServerConnectionFactory.this.getConnector();
                if (h3Connector instanceof NetworkConnector nc)
                {
                    int port = nc.getLocalPort();
                    if (port > 0)
                        responseHeaders.add(HttpHeader.ALT_SVC, String.format("h3=\":%d\"", port));
                }
            }
            return request;
        }
    }

    private static class HTTP3SessionListener implements HTTP3SessionServer.Listener
    {
        private static final Logger LOG = LoggerFactory.getLogger(HTTP3SessionListener.class);

        @Override
        public Stream.Server.Listener onRequest(Session.Server session, HeadersFrame frame)
        {
            return new HTTP3StreamListener();
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

        @Override
        public void onStreamFailure(Stream stream, Throwable failure)
        {
            HTTP3Stream http3Stream = (HTTP3Stream)stream;
            ServerHTTP3StreamConnection connection = (ServerHTTP3StreamConnection)http3Stream.getStreamEndPoint().getConnection();
            Runnable task = connection.onFailure(http3Stream, failure);
            Executor executor = http3Stream.getSession().getProtocolSession().getExecutor();
            ThreadPool.executeImmediately(executor, task);
        }
    }

    private static class HTTP3StreamListener implements Stream.Server.Listener, Invocable
    {
        private HTTP3Stream http3Stream;

        @Override
        public void onRequest(Stream.Server stream, HeadersFrame frame)
        {
            HTTP3StreamServer http3Stream = (HTTP3StreamServer)stream;
            this.http3Stream = http3Stream;
            getConnection().onRequest(http3Stream, frame);
        }

        @Override
        public void onDataAvailable(Stream.Server stream, boolean immediate)
        {
            ServerHTTP3StreamConnection connection = getConnection();
            connection.onDataAvailable(http3Stream, immediate);
        }

        @Override
        public void onTrailer(Stream.Server stream, HeadersFrame frame)
        {
            ServerHTTP3StreamConnection connection = getConnection();
            connection.onTrailer(http3Stream, frame);
        }

        @Override
        public void onIdleTimeout(Stream.Server stream, TimeoutException timeout, Promise<Boolean> promise)
        {
            getConnection().onIdleTimeout(http3Stream, timeout, (task, timedOut) ->
            {
                if (task == null)
                {
                    promise.succeeded(timedOut);
                    return;
                }
                Executor executor = http3Stream.getSession().getProtocolSession().getExecutor();
                ThreadPool.executeImmediately(executor, () ->
                {
                    try
                    {
                        task.run();
                        promise.succeeded(timedOut);
                    }
                    catch (Throwable x)
                    {
                        promise.failed(x);
                    }
                });
            });
        }

        @Override
        public void onFailure(Stream.Server stream, long error, Throwable failure)
        {
            Runnable task = getConnection().onFailure(http3Stream, failure);
            Executor executor = http3Stream.getSession().getProtocolSession().getExecutor();
            ThreadPool.executeImmediately(executor, task);
        }

        @Override
        public InvocationType getInvocationType()
        {
            HttpStreamOverHTTP3 httpStream = (HttpStreamOverHTTP3)http3Stream.getAttachment();
            return httpStream.getHttpChannel().getInvocationType();
        }

        private ServerHTTP3StreamConnection getConnection()
        {
            return (ServerHTTP3StreamConnection)http3Stream.getStreamEndPoint().getConnection();
        }
    }
}
