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

package org.eclipse.jetty.quic.quiche;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;

/**
 * <p>Provides different bindings, loaded via {@link java.util.ServiceLoader}, that access the native Quiche library.</p>
 */
public interface QuicheBinding
{
    Throwable initialize();

    int priority();

    byte[] fromPacket(ByteBuffer packet);

    Quiche connect(QuicheConfig quicheConfig, InetSocketAddress local, InetSocketAddress peer, int connectionIdLength) throws IOException;

    boolean negotiate(Quiche.TokenMinter tokenMinter, ByteBuffer packetRead, ByteBuffer packetToSend) throws IOException;

    Quiche tryAccept(QuicheConfig quicheConfig, Quiche.TokenValidator tokenValidator, ByteBuffer packetRead, SocketAddress local, SocketAddress peer) throws IOException;
}
