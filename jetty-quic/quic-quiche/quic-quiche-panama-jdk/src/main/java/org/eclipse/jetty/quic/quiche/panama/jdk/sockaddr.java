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

package org.eclipse.jetty.quic.quiche.panama.jdk;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;

import jdk.incubator.foreign.MemorySegment;
import jdk.incubator.foreign.ResourceScope;

public class sockaddr
{
    // TODO: linux-specific constants
    private static final short AF_INET = 2;
    private static final short AF_INET6 = 10;

    public static MemorySegment convert(SocketAddress socketAddress, ResourceScope scope)
    {
        if (!(socketAddress instanceof InetSocketAddress))
            throw new IllegalArgumentException("Expected InetSocketAddress instance, got: " + socketAddress);
        InetSocketAddress inetSocketAddress = (InetSocketAddress)socketAddress;
        InetAddress address = inetSocketAddress.getAddress();
        if (address instanceof Inet4Address)
        {
            // TODO: linux-specific implementation
            MemorySegment sin = sockaddr_in.allocate(scope);
            sockaddr_in.set_sin_family(sin, AF_INET);
            sockaddr_in.set_sin_port(sin, (short) inetSocketAddress.getPort());
            sockaddr_in.set_sin_addr(sin, ByteBuffer.wrap(address.getAddress()).getInt());
            return sin;
        }
        else if (address instanceof Inet6Address)
        {
            // TODO: linux-specific implementation
            MemorySegment sin6 = sockaddr_in6.allocate(scope);
            sockaddr_in6.set_sin6_family(sin6, AF_INET6);
            sockaddr_in6.set_sin6_port(sin6, (short) inetSocketAddress.getPort());
            sockaddr_in6.set_sin6_addr(sin6, address.getAddress());
            sockaddr_in6.set_sin6_scope_id(sin6, 0);
            sockaddr_in6.set_sin6_flowinfo(sin6, 0);
            return sin6;
        }
        else
        {
            throw new UnsupportedOperationException("Unsupported InetAddress: " + address);
        }
    }
}
