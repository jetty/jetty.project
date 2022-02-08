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

package org.eclipse.jetty.quic.quiche.jna.macos;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;

import com.sun.jna.Structure;
import org.eclipse.jetty.quic.quiche.jna.SizedStructure;
import org.eclipse.jetty.quic.quiche.jna.size_t;
import org.eclipse.jetty.quic.quiche.jna.sockaddr;
import org.eclipse.jetty.quic.quiche.jna.uint16_t;
import org.eclipse.jetty.quic.quiche.jna.uint32_t;
import org.eclipse.jetty.quic.quiche.jna.uint8_t;

public interface netinet_macos
{
    uint8_t AF_INET = new uint8_t((byte)2);
    uint8_t AF_INET6 = new uint8_t((byte)30);

    static SizedStructure<sockaddr> to_sock_addr(SocketAddress socketAddress)
    {
        if (!(socketAddress instanceof InetSocketAddress))
            throw new IllegalArgumentException("Expected InetSocketAddress instance, got: " + socketAddress);
        InetSocketAddress inetSocketAddress = (InetSocketAddress)socketAddress;
        InetAddress address = inetSocketAddress.getAddress();
        if (address instanceof Inet4Address)
        {
            sockaddr_in sin = new sockaddr_in();
            sin.sin_len = new uint8_t((byte)sin.size());
            sin.sin_family = AF_INET;
            sin.sin_addr = new uint32_t(ByteBuffer.wrap(address.getAddress()).getInt());
            sin.sin_port = new uint16_t(inetSocketAddress.getPort());
            return new SizedStructure<>(sin.to_sockaddr(), new size_t(sin.size()));
        }
        else if (address instanceof Inet6Address)
        {
            sockaddr_in6 sin6 = new sockaddr_in6();
            sin6.sin6_len = new uint8_t((byte)sin6.size());
            sin6.sin6_family = AF_INET6;
            System.arraycopy(address.getAddress(), 0, sin6.sin6_addr, 0, sin6.sin6_addr.length);
            sin6.sin6_port = new uint16_t(inetSocketAddress.getPort());
            sin6.sin6_flowinfo = new uint32_t(0);
            sin6.sin6_scope_id = new uint32_t(0);
            return new SizedStructure<>(sin6.to_sockaddr(), new size_t(sin6.size()));
        }
        else
        {
            throw new UnsupportedOperationException("Unsupported InetAddress: " + address);
        }
    }

    @Structure.FieldOrder({"sin_len", "sin_family", "sin_port", "sin_addr", "sin_zero"})
    class sockaddr_in extends Structure
    {
        public uint8_t sin_len;
        public uint8_t sin_family;
        public uint16_t sin_port;
        public uint32_t sin_addr;
        public byte[] sin_zero = new byte[8]; // padding to have the same size as sockaddr

        public sockaddr to_sockaddr()
        {
            write();
            return new sockaddr(getPointer());
        }
    }

    @Structure.FieldOrder({"sin6_len", "sin6_family", "sin6_port", "sin6_flowinfo", "sin6_addr", "sin6_scope_id"})
    class sockaddr_in6 extends Structure
    {
        public uint8_t  sin6_len;
        public uint8_t  sin6_family;
        public uint16_t sin6_port;
        public uint32_t sin6_flowinfo;
        public byte[]   sin6_addr = new byte[16];
        public uint32_t sin6_scope_id;

        public sockaddr to_sockaddr()
        {
            write();
            return new sockaddr(getPointer());
        }
    }
}
