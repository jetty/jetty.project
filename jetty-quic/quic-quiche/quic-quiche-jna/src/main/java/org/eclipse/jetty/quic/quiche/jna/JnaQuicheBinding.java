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

package org.eclipse.jetty.quic.quiche.jna;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;

import org.eclipse.jetty.quic.quiche.QuicheBinding;
import org.eclipse.jetty.quic.quiche.QuicheConfig;
import org.eclipse.jetty.quic.quiche.QuicheConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JnaQuicheBinding implements QuicheBinding
{
    private static final Logger LOG = LoggerFactory.getLogger(JnaQuicheBinding.class);

    @Override
    public boolean isUsable()
    {
        try
        {
            // Make a Quiche call to confirm.
            LibQuiche.INSTANCE.quiche_version();
            return true;
        }
        catch (Throwable x)
        {
            LOG.debug("JNA quiche binding is not usable", x);
            return false;
        }
    }

    @Override
    public int priority()
    {
        return 200;
    }

    @Override
    public byte[] fromPacket(ByteBuffer packet)
    {
        return JnaQuicheConnection.fromPacket(packet);
    }

    @Override
    public QuicheConnection connect(QuicheConfig quicheConfig, InetSocketAddress peer, int connectionIdLength) throws IOException
    {
        return JnaQuicheConnection.connect(quicheConfig, peer, connectionIdLength);
    }

    @Override
    public boolean negotiate(QuicheConnection.TokenMinter tokenMinter, ByteBuffer packetRead, ByteBuffer packetToSend) throws IOException
    {
        return JnaQuicheConnection.negotiate(tokenMinter, packetRead, packetToSend);
    }

    @Override
    public QuicheConnection tryAccept(QuicheConfig quicheConfig, QuicheConnection.TokenValidator tokenValidator, ByteBuffer packetRead, SocketAddress peer) throws IOException
    {
        return JnaQuicheConnection.tryAccept(quicheConfig, tokenValidator, packetRead, peer);
    }

    @Override
    public String toString()
    {
        return getClass().getSimpleName() + "{p=" + priority() + " u=" + isUsable() + "}";
    }
}
