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

package org.eclipse.jetty.http3.client.internal;

import org.eclipse.jetty.http3.internal.HTTP3StreamConnection;
import org.eclipse.jetty.http3.internal.parser.MessageParser;
import org.eclipse.jetty.quic.common.QuicStreamEndPoint;

public class ClientHTTP3StreamConnection extends HTTP3StreamConnection
{
    public ClientHTTP3StreamConnection(QuicStreamEndPoint endPoint, ClientHTTP3Session session, MessageParser parser)
    {
        super(endPoint, session.getQuicSession().getExecutor(), session.getQuicSession().getByteBufferPool(), parser);
    }
}
