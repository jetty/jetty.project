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

package org.eclipse.jetty.quic.quiche;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;

public interface QuicheBinding
{
    boolean isUsable();
    int priority();

    byte[] fromPacket(ByteBuffer packet);
    QuicheConnection connect(QuicheConfig quicheConfig, InetSocketAddress peer, int connectionIdLength) throws IOException;
    boolean negotiate(QuicheConnection.TokenMinter tokenMinter, ByteBuffer packetRead, ByteBuffer packetToSend) throws IOException;
    QuicheConnection tryAccept(QuicheConfig quicheConfig, QuicheConnection.TokenValidator tokenValidator, ByteBuffer packetRead, SocketAddress peer) throws IOException;
}
