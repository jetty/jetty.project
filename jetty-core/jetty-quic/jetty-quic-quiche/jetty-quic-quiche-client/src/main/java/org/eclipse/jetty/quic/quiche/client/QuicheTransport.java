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

package org.eclipse.jetty.quic.quiche.client;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.Transport;
import org.eclipse.jetty.quic.api.Session;
import org.eclipse.jetty.quic.api.Stream;
import org.eclipse.jetty.quic.api.frames.ConnectionCloseFrame;
import org.eclipse.jetty.quic.api.frames.Frame;
import org.eclipse.jetty.quic.client.ClientProtocolSession;
import org.eclipse.jetty.quic.common.AbstractSession;
import org.eclipse.jetty.quic.common.ProtocolSession;
import org.eclipse.jetty.quic.common.ProtocolStreamListener;
import org.eclipse.jetty.quic.quiche.client.internal.ClientQuicheSession;
import org.eclipse.jetty.quic.util.ErrorCode;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.component.Container;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A {@link Transport} for QUIC that uses the Quiche library and delegates to another {@code Transport}.</p>
 * <p>By default, the delegate is {@link Transport#UDP_IP}, but it may be a different implementation.</p>
 */
public class QuicheTransport extends Transport.Wrapper
{
    private static final Logger LOG = LoggerFactory.getLogger(QuicheTransport.class);

    private final QuicheClientQuicConfiguration quicConfiguration;

    public QuicheTransport(QuicheClientQuicConfiguration quicConfiguration)
    {
        this(UDP_IP, quicConfiguration);
    }

    public QuicheTransport(Transport wrapped, QuicheClientQuicConfiguration quicConfiguration)
    {
        super(wrapped);
        this.quicConfiguration = quicConfiguration;
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
        return new QuicheClientConnectionFactory(factory, quicConfiguration);
    }

    @Override
    public Connection newConnection(EndPoint endPoint, Map<String, Object> context) throws IOException
    {
        context.put(Session.Listener.class.getName(), new ProtocolSessionListener(context));
        return super.newConnection(endPoint, context);
    }

    @Override
    public int hashCode()
    {
        return getWrapped().hashCode();
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
            return true;
        if (obj instanceof QuicheTransport that)
            return Objects.equals(getWrapped(), that.getWrapped());
        return false;
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
                ClientQuicheSession qSession = (ClientQuicheSession)session;
                ProtocolSession pSession = newProtocolSession(qSession);
                qSession.addManaged(pSession);
                protocolSession.set(pSession);
                context.put(ClientConnector.APPLICATION_PROTOCOL_CONTEXT_KEY, qSession.getNegotiatedProtocol());
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("could not create ProtocolSession", x);
                session.disconnect(new ConnectionCloseFrame(ErrorCode.APPLICATION_ERROR.code(), "invalid_protocol"), x, Promise.Invocable.noop());
            }
        }

        @Override
        public Stream.Listener onNewStream(Session session, Frame.WithStreamId frame)
        {
            return new ProtocolStreamListener.Server(protocolSession.get());
        }

        @Override
        public boolean onIdleTimeout(Session session, TimeoutException failure)
        {
            ProtocolSession pSession = protocolSession.get();
            if (pSession != null)
                return pSession.onIdleTimeout(failure);
            return true;
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
        public void onLocalClose(Session session, ConnectionCloseFrame frame, Promise.Invocable<Session> promise)
        {
            ProtocolSession pSession = protocolSession.get();
            if (pSession != null)
                pSession.close(frame, Promise.Invocable.toPromise(promise, ps -> session));
            else
                promise.succeeded(session);
        }

        @Override
        public void onClose(Session session, ConnectionCloseFrame frame)
        {
            ProtocolSession pSession = protocolSession.get();
            if (pSession != null)
                pSession.onClose(frame);
        }

        private ProtocolSession newProtocolSession(ClientQuicheSession session)
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
