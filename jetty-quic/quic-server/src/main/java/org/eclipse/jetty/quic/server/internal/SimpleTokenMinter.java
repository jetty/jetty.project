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

package org.eclipse.jetty.quic.server.internal;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.quic.quiche.QuicheConnection;

public class SimpleTokenMinter implements QuicheConnection.TokenMinter
{
    private final InetSocketAddress inetSocketAddress;
    private final byte[] implName = "jetty".getBytes(StandardCharsets.US_ASCII);

    public SimpleTokenMinter(InetSocketAddress inetSocketAddress)
    {
        this.inetSocketAddress = inetSocketAddress;
    }

    @Override
    public byte[] mint(byte[] dcid, int len)
    {
        byte[] address = inetSocketAddress.getAddress().getAddress();
        int port = inetSocketAddress.getPort();

        ByteBuffer token = ByteBuffer.allocate(implName.length + address.length + 2 + len);

        token.put(implName);
        token.put(address);
        token.putShort((short)port);
        token.put(dcid, 0, len);

        return token.array();
    }
}
