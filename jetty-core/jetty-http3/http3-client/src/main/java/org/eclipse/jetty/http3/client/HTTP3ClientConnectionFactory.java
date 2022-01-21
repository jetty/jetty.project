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

package org.eclipse.jetty.http3.client;

import java.util.Map;

import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.client.internal.ClientHTTP3Session;
import org.eclipse.jetty.http3.client.internal.ClientHTTP3StreamConnection;
import org.eclipse.jetty.http3.internal.parser.MessageParser;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.quic.client.ClientQuicSession;
import org.eclipse.jetty.quic.common.ProtocolSession;
import org.eclipse.jetty.quic.common.QuicSession;
import org.eclipse.jetty.quic.common.QuicStreamEndPoint;
import org.eclipse.jetty.util.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A {@link ClientConnectionFactory} implementation that creates HTTP/3 specific
 * {@link Connection} objects to be linked to a {@link QuicStreamEndPoint}.</p>
 */
public class HTTP3ClientConnectionFactory implements ClientConnectionFactory, ProtocolSession.Factory
{
    private static final Logger LOG = LoggerFactory.getLogger(HTTP3ClientConnectionFactory.class);

    @Override
    public ProtocolSession newProtocolSession(QuicSession quicSession, Map<String, Object> context)
    {
        HTTP3Client client = (HTTP3Client)context.get(HTTP3Client.CLIENT_CONTEXT_KEY);
        Session.Client.Listener listener = (Session.Client.Listener)context.get(HTTP3Client.SESSION_LISTENER_CONTEXT_KEY);
        @SuppressWarnings("unchecked")
        Promise<Session.Client> promise = (Promise<Session.Client>)context.get(HTTP3Client.SESSION_PROMISE_CONTEXT_KEY);
        ClientHTTP3Session session = new ClientHTTP3Session(client.getHTTP3Configuration(), (ClientQuicSession)quicSession, listener, promise);
        if (LOG.isDebugEnabled())
            LOG.debug("created protocol-specific {}", session);
        return session;
    }

    @Override
    public Connection newConnection(EndPoint endPoint, Map<String, Object> context)
    {
        QuicStreamEndPoint streamEndPoint = (QuicStreamEndPoint)endPoint;
        long streamId = streamEndPoint.getStreamId();
        ClientHTTP3Session http3Session = (ClientHTTP3Session)streamEndPoint.getQuicSession().getProtocolSession();
        MessageParser parser = new MessageParser(http3Session.getSessionClient(), http3Session.getQpackDecoder(), streamId, streamEndPoint::isStreamFinished);
        ClientHTTP3StreamConnection connection = new ClientHTTP3StreamConnection(streamEndPoint, http3Session, parser);
        return customize(connection, context);
    }
}
