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

package org.eclipse.jetty.http3.server;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;

public class AddressCodec
{
    public static final int ENCODED_ADDRESS_LENGTH = 19;

    public static ByteBuffer encodeInetSocketAddress(ByteBufferPool byteBufferPool, InetSocketAddress remoteAddress) throws IOException
    {
        ByteBuffer addressBuffer = byteBufferPool.acquire(ENCODED_ADDRESS_LENGTH, true);
        try
        {
            int pos = BufferUtil.flipToFill(addressBuffer);
            encodeInetSocketAddress(addressBuffer, remoteAddress);
            BufferUtil.flipToFlush(addressBuffer, pos);
            return addressBuffer;
        }
        catch (Throwable x)
        {
            byteBufferPool.release(addressBuffer);
            throw x;
        }
    }

    public static InetSocketAddress decodeInetSocketAddress(ByteBuffer buffer) throws IOException
    {
        int headerPosition = buffer.position();
        byte ipVersion = buffer.get();
        byte[] address;
        if (ipVersion == 4)
            address = new byte[4];
        else if (ipVersion == 6)
            address = new byte[16];
        else
            throw new IOException("Unsupported IP version: " + ipVersion);
        buffer.get(address);
        int port = buffer.getChar();
        buffer.position(headerPosition + ENCODED_ADDRESS_LENGTH);
        return new InetSocketAddress(InetAddress.getByAddress(address), port);
    }

    public static void encodeInetSocketAddress(ByteBuffer buffer, InetSocketAddress peer) throws IOException
    {
        int headerPosition = buffer.position();
        byte[] addressBytes = peer.getAddress().getAddress();
        int port = peer.getPort();
        byte ipVersion;
        if (peer.getAddress() instanceof Inet4Address)
            ipVersion = 4;
        else if (peer.getAddress() instanceof Inet6Address)
            ipVersion = 6;
        else
            throw new IOException("Unsupported address type: " + peer.getAddress().getClass());
        buffer.put(ipVersion);
        buffer.put(addressBytes);
        buffer.putChar((char)port);
        buffer.position(headerPosition + ENCODED_ADDRESS_LENGTH);
    }
}
