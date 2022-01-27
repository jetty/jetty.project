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
import java.util.Arrays;

import org.eclipse.jetty.quic.quiche.QuicheConnection;

public class SimpleTokenValidator implements QuicheConnection.TokenValidator
{
    private final InetSocketAddress inetSocketAddress;
    private final byte[] implName = "jetty".getBytes(StandardCharsets.US_ASCII);

    public SimpleTokenValidator(InetSocketAddress inetSocketAddress)
    {
        this.inetSocketAddress = inetSocketAddress;
    }

    @Override
    public byte[] validate(byte[] token, int len)
    {
        ByteBuffer byteBuffer = ByteBuffer.wrap(token).limit(len);

        if (byteBuffer.remaining() < implName.length)
            return null;

        byte[] subTokenMarker = new byte[implName.length];
        byteBuffer.get(subTokenMarker);
        if (!Arrays.equals(subTokenMarker, implName))
            return null;

        byte[] address = inetSocketAddress.getAddress().getAddress();
        if (byteBuffer.remaining() < address.length)
            return null;

        byte[] subTokenAddress = new byte[address.length];
        byteBuffer.get(subTokenAddress);
        if (!Arrays.equals(subTokenAddress, address))
            return null;

        byte[] port = ByteBuffer.allocate(Short.BYTES).putShort((short)inetSocketAddress.getPort()).array();
        if (byteBuffer.remaining() < port.length)
            return null;

        byte[] subTokenPort = new byte[port.length];
        byteBuffer.get(subTokenPort);
        if (!Arrays.equals(port, subTokenPort))
            return null;

        byte[] odcid = new byte[byteBuffer.remaining()];
        byteBuffer.get(odcid);

        return odcid;
    }
}
