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

package org.eclipse.jetty.quic.quiche.server;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.quic.api.Session;
import org.eclipse.jetty.quic.api.Stream;
import org.eclipse.jetty.quic.api.frames.ConnectionCloseFrame;
import org.eclipse.jetty.quic.api.frames.Frame;
import org.eclipse.jetty.quic.common.AbstractSession;
import org.eclipse.jetty.quic.common.ProtocolSession;
import org.eclipse.jetty.quic.common.ProtocolStreamListener;
import org.eclipse.jetty.quic.quiche.server.internal.ServerQuicheSession;
import org.eclipse.jetty.quic.server.ServerProtocolSession;
import org.eclipse.jetty.quic.util.ErrorCode;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A {@link ConnectionFactory} for QUIC that uses the Quiche library.</p>
 */
public class QuicheServerConnectionFactory extends AbstractQuicheServerConnectionFactory implements ConnectionFactory.Configuring
{
    private static final Logger LOG = LoggerFactory.getLogger(QuicheServerConnectionFactory.class);

    public QuicheServerConnectionFactory(SslContextFactory.Server sslContextFactory, QuicheServerQuicConfiguration quicConfiguration)
    {
        super(sslContextFactory, quicConfiguration, new ProtocolSessionListenerFactory());
    }

    @Override
    public void configure(Connector connector)
    {
        Session.Listener.Factory factory = getSessionListenerFactory();
        if (factory instanceof ConnectionFactory.Configuring c)
            c.configure(connector);
    }

    private static class ProtocolSessionListenerFactory implements Session.Listener.Factory, ConnectionFactory.Configuring
    {
        private Connector connector;

        @Override
        public void configure(Connector connector)
        {
            this.connector = connector;
        }

        @Override
        public Session.Listener newListener()
        {
            return new ProtocolSessionListener(connector);
        }
    }

    private static class ProtocolSessionListener implements AbstractSession.Listener
    {
        private final AtomicReference<ProtocolSession> protocolSession = new AtomicReference<>();
        private final Connector connector;

        private ProtocolSessionListener(Connector connector)
        {
            this.connector = connector;
        }

        @Override
        public void onOpen(Session session)
        {
            try
            {
                ServerQuicheSession qSession = (ServerQuicheSession)session;
                ProtocolSession pSession = newProtocolSession(qSession);
                qSession.addManaged(pSession);
                protocolSession.set(pSession);
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("could not create ProtocolSession", x);
                session.disconnect(ErrorCode.APPLICATION_ERROR.code(), "invalid_protocol", x, Callback.NOOP);
            }
        }

        @Override
        public Stream.Listener onNewStream(Session session, Frame.WithStreamId frame)
        {
            return new ProtocolStreamListener.Server(protocolSession.get());
        }

        @Override
        public CompletableFuture<Session> onLocalShutdown(Session session)
        {
            ProtocolSession pSession = protocolSession.get();
            if (pSession != null)
                return pSession.shutdown().thenApply(ps -> session);
            return CompletableFuture.completedFuture(session);
        }

        @Override
        public void onLocalClose(Session session, long appError, String reason, Callback callback)
        {
            ProtocolSession pSession = protocolSession.get();
            if (pSession != null)
                pSession.close(appError, reason, callback);
            else
                callback.succeeded();
        }

        @Override
        public void onClose(Session session, ConnectionCloseFrame frame)
        {
            ProtocolSession pSession = protocolSession.get();
            if (pSession != null)
                pSession.onClose(frame);
        }

        private ProtocolSession newProtocolSession(ServerQuicheSession session)
        {
            String protocol = session.getNegotiatedProtocol();
            if (protocol == null)
                throw new IllegalStateException("missing application protocol");
            ConnectionFactory connectionFactory = connector.getConnectionFactory(protocol);
            if (connectionFactory == null)
                throw new IllegalStateException("missing ConnectionFactory for protocol " + protocol);
            ProtocolSession pSession;
            if (connectionFactory instanceof ProtocolSession.Factory psf)
                pSession = psf.newProtocolSession(session, Map.of());
            else
                pSession = new ServerProtocolSession(connector, session, connectionFactory);
            return pSession;
        }
    }
}
