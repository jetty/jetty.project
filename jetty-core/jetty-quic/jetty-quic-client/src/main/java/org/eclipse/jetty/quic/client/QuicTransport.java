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
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
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
import org.eclipse.jetty.quic.util.ErrorCode;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.component.Container;
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
        // TODO: when coming from QuicClient, we arrive here with the context set up already,
        //  and we just want to delegate to super.
        //  However, we arrive here also from HttpClient/HTTP2Client/HTTP3Client, and we need
        //  to set up QUIC-specific parameters that are typically set up in QuicClient, but
        //  we don't want to duplicate code, nor overwrite existing parameters, nor recursing
        //  and calling client.connect() again.

//        Session.Listener listener = (Session.Listener)context.get(QuicClient.SESSION_LISTENER_CONTEXT_KEY);
//        if (listener == null)
//            listener = new ProtocolSessionListener(context);
        super.connect(socketAddress, context);
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
                session.disconnect(new ConnectionCloseFrame(ErrorCode.INTERNAL_ERROR.code(), "invalid_protocol"), x, Promise.Invocable.noop());
            }
        }

        @Override
        public Stream.Listener onNewStream(Session session, Frame.WithStreamId frame)
        {
            // TODO: this needs to be done properly.
            //  The StreamEP should be created in ProtocolStreamListener.Client.onNewStream()
            //  rather than here, because here we don't have the Stream object.
            return new ProtocolStreamListener.Client(null/*TODO*/);
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
