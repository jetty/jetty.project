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

package org.eclipse.jetty.quic.quiche.ffi.linux;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import org.eclipse.jetty.quic.quiche.ffi.uint16_t;
import org.eclipse.jetty.quic.quiche.ffi.uint32_t;

public interface netinet_linux
{
    uint16_t AF_INET = new uint16_t(2);
    uint16_t AF_INET6 = new uint16_t(10);

    static sockaddr_in to_sock_addr(SocketAddress socketAddress)
    {
        if (!(socketAddress instanceof InetSocketAddress))
            throw new IllegalArgumentException("Expected InetSocketAddress instance, got: " + socketAddress);
        InetSocketAddress inetSocketAddress = (InetSocketAddress)socketAddress;
        InetAddress address = inetSocketAddress.getAddress();
        if (!(address instanceof Inet4Address))
            throw new UnsupportedOperationException("TODO: only ipv4 is supported for now");

        sockaddr_in sa = new sockaddr_in();
        sa.sin_family = AF_INET;
        sa.sin_addr = new uint32_t(ByteBuffer.wrap(address.getAddress()).order(ByteOrder.nativeOrder()).getInt());
        sa.sin_port = new uint16_t(inetSocketAddress.getPort());
        return sa;
    }

    @Structure.FieldOrder({"sa_family", "sa_data"})
    class sockaddr extends Structure
    {
        public uint16_t sa_family;
        public byte[] sa_data = new byte[14]; // 14 bytes of protocol address
    }

    @Structure.FieldOrder({"sin_family", "sin_port", "sin_addr", "sin_zero"})
    class sockaddr_in extends Structure
    {
        public uint16_t sin_family;
        public uint16_t sin_port;
        public uint32_t sin_addr;
        public byte[] sin_zero = new byte[8]; // padding to have the same size as sockaddr

        public sockaddr_in()
        {
        }

        private sockaddr_in(Pointer p)
        {
            super(p);
            read();
        }

        public static ByReference byReference(sockaddr_in inet)
        {
            inet.write();
            return new ByReference(inet.getPointer());
        }

        public static class ByReference extends sockaddr_in implements Structure.ByReference
        {
            private ByReference(Pointer p)
            {
                super(p);
            }
        }
    }

    @Structure.FieldOrder({"sin6_family", "sin6_port", "sin6_flowinfo", "s6_addr", "sin6_scope_id"})
    class sockaddr_in6 extends Structure
    {
        public uint16_t sin6_family;
        public uint16_t sin6_port;
        public uint32_t sin6_flowinfo;
        public byte[] s6_addr = new byte[16];
        public uint32_t sin6_scope_id;
    }

    @Structure.FieldOrder({"ss_family", "ss_zero"})
    class sockaddr_storage extends Structure
    {
        public uint16_t ss_family;
        public byte[] ss_zero = new byte[126]; // padding
    }
}
