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

package org.eclipse.jetty.quic.client;

import java.net.SocketAddress;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.Transport;
import org.eclipse.jetty.quic.api.Session;
import org.eclipse.jetty.quic.api.Stream;
import org.eclipse.jetty.quic.api.frames.ConnectionCloseFrame;
import org.eclipse.jetty.quic.api.frames.Frame;
import org.eclipse.jetty.quic.client.internal.ClientQuicSession;
import org.eclipse.jetty.quic.client.internal.QuicClientConnectionFactory;
import org.eclipse.jetty.quic.common.AbstractSession;
import org.eclipse.jetty.quic.common.ProtocolSession;
import org.eclipse.jetty.quic.common.ProtocolStreamListener;
import org.eclipse.jetty.quic.common.QuicSession;
import org.eclipse.jetty.quic.util.ErrorCode;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.component.Container;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QuicTransport extends Transport.Wrapper
{
    private static final Logger LOG = LoggerFactory.getLogger(QuicTransport.class);
    private final QuicClient client;

    public QuicTransport(QuicClient client)
    {
        this(UDP_IP, client);
    }

    public QuicTransport(Transport wrapped, QuicClient client)
    {
        super(wrapped);
        this.client = client;
    }

    @Override
    public boolean isIntrinsicallySecure()
    {
        return true;
    }

    @Override
    public ClientConnectionFactory newClientConnectionFactory(ClientConnector connector, ClientConnectionFactory factory)
    {
        factory = super.newClientConnectionFactory(connector, factory);
        return new QuicClientConnectionFactory(factory, client.getClientQuicConfiguration());
    }

    @Override
    public void connect(SocketAddress socketAddress, Map<String, Object> context)
    {
        if (context.containsKey(QuicClient.CONTEXT_KEY))
        {
            super.connect(socketAddress, context);
        }
        else
        {
            SslContextFactory.Client clientTLS = (SslContextFactory.Client)context.get(ClientConnector.SSL_CONTEXT_FACTORY_CONTEXT_KEY);
            Session.Listener listener = new ProtocolSessionListener(context);
            @SuppressWarnings("unchecked")
            Promise<Connection> ioPromise = (Promise<Connection>)context.get(ClientConnector.CONNECTION_PROMISE_CONTEXT_KEY);
            // Link the QUIC session promise to the IO connection promise in case of failures.
            Promise<Session> promise = Promise.from(_ -> {}, ioPromise::failed);
            client.connect(this, clientTLS, socketAddress, null, listener, promise, context);
        }
    }

    private static class ProtocolSessionListener implements AbstractSession.Listener
    {
        private final AtomicReference<ProtocolSession> protocolSession = new AtomicReference<>();
        private final Map<String, Object> context;

        private ProtocolSessionListener(Map<String, Object> context)
        {
            this.context = context;
        }

        @Override
        public void onOpen(Session session)
        {
            try
            {
                ClientQuicSession qSession = (ClientQuicSession)session;
                ProtocolSession pSession = newProtocolSession(qSession);
                qSession.addManaged(pSession);
                protocolSession.set(pSession);
                context.put(ClientConnector.APPLICATION_PROTOCOL_CONTEXT_KEY, qSession.getApplicationProtocol());
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("could not create ProtocolSession", x);
                ((QuicSession)session).disconnect(ErrorCode.INTERNAL_ERROR.code(), "invalid_protocol", 0x00, x, Callback.NOOP);
            }
        }

        @Override
        public Stream.Listener onNewStream(Session session, Frame.WithStreamId frame)
        {
            return new ProtocolStreamListener.Remote(protocolSession.get());
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

        private ProtocolSession newProtocolSession(ClientQuicSession session)
        {
            // This is the ClientConnectionFactory for the protocol on top of QUIC,
            // likely the HttpClientTransport, or the proxy ClientConnectionFactory.
            ClientConnectionFactory connectionFactory = session.getClientConnectionFactory();

            ProtocolSession protocolSession = null;
            if (connectionFactory instanceof ProtocolSession.Factory psf)
                protocolSession = psf.newProtocolSession(session, context);
            if (protocolSession != null)
                return protocolSession;

            // Support for container ClientConnectionFactory that may speak multiple protocols.
            if (connectionFactory instanceof Container container)
            {
                for (ProtocolSession.Factory psf : container.getBeans(ProtocolSession.Factory.class))
                {
                    protocolSession = psf.newProtocolSession(session, context);
                    if (protocolSession != null)
                        return protocolSession;
                }
            }

            // Return the default ProtocolSession.
            ClientConnector connector = (ClientConnector)context.get(ClientConnector.CONTEXT_KEY);
            return new ClientProtocolSession(connector, session, connectionFactory, context);
        }
    }
}
