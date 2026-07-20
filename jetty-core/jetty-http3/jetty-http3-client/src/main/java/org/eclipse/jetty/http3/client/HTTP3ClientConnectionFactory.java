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

package org.eclipse.jetty.http3.client;

import java.util.Map;

import org.eclipse.jetty.http3.client.internal.ClientHTTP3Session;
import org.eclipse.jetty.http3.client.internal.ClientHTTP3StreamConnection;
import org.eclipse.jetty.http3.parser.MessageParser;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.RateControl;
import org.eclipse.jetty.quic.common.ProtocolSession;
import org.eclipse.jetty.quic.common.StreamEndPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A {@link ClientConnectionFactory} implementation that creates HTTP/3 specific
 * {@link Connection} objects to be linked to a {@link StreamEndPoint}.</p>
 */
public class HTTP3ClientConnectionFactory implements ClientConnectionFactory, ProtocolSession.Factory
{
    private static final Logger LOG = LoggerFactory.getLogger(HTTP3ClientConnectionFactory.class);

    @Override
    public ProtocolSession newProtocolSession(org.eclipse.jetty.quic.api.Session quicSession, Map<String, Object> context)
    {
        HTTP3Client client = (HTTP3Client)context.get(HTTP3Client.CONTEXT_KEY);
        ClientHTTP3Session h3Session = new ClientHTTP3Session(client.getClientConnector(), client.getHTTP3Configuration(), quicSession, this, context);
        if (LOG.isDebugEnabled())
            LOG.debug("created protocol-specific {}", h3Session);
        return h3Session;
    }

    @Override
    public Connection newConnection(EndPoint endPoint, Map<String, Object> context)
    {
        StreamEndPoint streamEndPoint = (StreamEndPoint)endPoint;
        long streamId = streamEndPoint.getStream().getId();
        ClientHTTP3Session http3Session = (ClientHTTP3Session)streamEndPoint.getProtocolSession();
        RateControl rateControl = http3Session.getHTTP3Configuration().getRateControlFactory().newRateControl(endPoint);
        MessageParser parser = new MessageParser(rateControl, http3Session.getSessionClient().getParserListener(), http3Session.getQpackDecoder(), streamId);
        ClientHTTP3StreamConnection connection = new ClientHTTP3StreamConnection(streamEndPoint, http3Session, parser);
        return customize(connection, context);
    }
}
