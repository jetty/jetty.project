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

package org.eclipse.jetty.http3.internal;

import java.util.concurrent.Executor;

import org.eclipse.jetty.http3.internal.parser.Parser;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.quic.common.ProtocolQuicSession;

public class HTTP3Connection extends AbstractConnection
{
    private final ProtocolQuicSession protocolSession;
    private final Parser parser;

    public HTTP3Connection(EndPoint endPoint, Executor executor, Parser parser)
    {
        super(endPoint, executor);
        this.protocolSession = null; // TODO
        this.parser = parser;
    }

    @Override
    public void onOpen()
    {
        super.onOpen();
        fillInterested();
    }

    @Override
    public void onFillable()
    {
    }

    // TODO
    //  Output side.
    //  When responses want to send a HEADERS frame,
    //  they cannot generate the bytes and write them to the EP because otherwise they will be accessing the QpackEncoder concurrently.
    //  Therefore we need to have a reference from here back to ProtocolQuicSession and do
    //  protocolQuicSession.append(frames);
    //  Then ProtocolQuicSession will have a Flusher that will generate the bytes in a single threaded way.
}
