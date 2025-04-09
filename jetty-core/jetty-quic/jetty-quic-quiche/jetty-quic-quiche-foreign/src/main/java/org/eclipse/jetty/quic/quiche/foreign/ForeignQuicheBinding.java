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

package org.eclipse.jetty.quic.quiche.foreign;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;

import org.eclipse.jetty.quic.quiche.QuicheBinding;
import org.eclipse.jetty.quic.quiche.QuicheConfig;
import org.eclipse.jetty.quic.quiche.QuicheConnection;

public class ForeignQuicheBinding implements QuicheBinding
{
    private Throwable failure;

    @Override
    public Throwable initialize()
    {
        try
        {
            quiche_h.initialize();
            failure = null;
        }
        catch (ExceptionInInitializerError e)
        {
            Throwable cause = e.getCause();
            failure = cause != null ? cause : e;
        }
        catch (Throwable x)
        {
            failure = x;
        }
        return failure;
    }

    @Override
    public int priority()
    {
        return 100;
    }

    @Override
    public byte[] fromPacket(ByteBuffer packet)
    {
        return ForeignQuicheConnection.fromPacket(packet);
    }

    @Override
    public QuicheConnection connect(QuicheConfig quicheConfig, InetSocketAddress local, InetSocketAddress peer, int connectionIdLength) throws IOException
    {
        return ForeignQuicheConnection.connect(quicheConfig, local, peer, connectionIdLength);
    }

    @Override
    public boolean negotiate(QuicheConnection.TokenMinter tokenMinter, ByteBuffer packetRead, ByteBuffer packetToSend) throws IOException
    {
        return ForeignQuicheConnection.negotiate(tokenMinter, packetRead, packetToSend);
    }

    @Override
    public QuicheConnection tryAccept(QuicheConfig quicheConfig, QuicheConnection.TokenValidator tokenValidator, ByteBuffer packetRead, SocketAddress local, SocketAddress peer) throws IOException
    {
        return ForeignQuicheConnection.tryAccept(quicheConfig, tokenValidator, packetRead, local, peer);
    }

    @Override
    public String toString()
    {
        return getClass().getSimpleName() + "{p=" + priority() + " f=" + failure + "}";
    }
}
