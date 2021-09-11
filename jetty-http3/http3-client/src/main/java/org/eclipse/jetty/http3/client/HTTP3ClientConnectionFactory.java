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

import java.io.IOException;
import java.util.Map;

import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.quic.client.ClientQuicSession;
import org.eclipse.jetty.quic.common.ProtocolQuicSession;
import org.eclipse.jetty.quic.common.QuicSession;

public class HTTP3ClientConnectionFactory implements ClientConnectionFactory, ProtocolQuicSession.Factory
{
    @Override
    public ProtocolQuicSession newProtocolQuicSession(QuicSession quicSession, Map<String, Object> context)
    {
        HTTP3Client http3Client = (HTTP3Client)context.get(HTTP3Client.CLIENT_CONTEXT_KEY);
        // TODO: configure the QpackDecoder.maxHeaderSize from HTTP3Client
        return new HTTP3ClientQuicSession((ClientQuicSession)quicSession);
    }

    @Override
    public Connection newConnection(EndPoint endPoint, Map<String, Object> context) throws IOException
    {
        return null;
    }
}
