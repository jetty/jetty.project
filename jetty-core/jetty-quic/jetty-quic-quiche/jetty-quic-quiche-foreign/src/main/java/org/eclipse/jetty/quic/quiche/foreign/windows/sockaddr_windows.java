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

package org.eclipse.jetty.quic.quiche.foreign.windows;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.invoke.VarHandle;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.eclipse.jetty.quic.quiche.foreign.NativeHelper.C_CHAR;
import static org.eclipse.jetty.quic.quiche.foreign.NativeHelper.C_INT;
import static org.eclipse.jetty.quic.quiche.foreign.NativeHelper.C_SHORT;

public class sockaddr_windows
{
    private static final short AF_INET = 2;
    private static final short AF_INET6 = 23;

    public static MemorySegment convert(SocketAddress socketAddress, SegmentAllocator scope)
    {
        if (!(socketAddress instanceof InetSocketAddress))
            throw new IllegalArgumentException("Expected InetSocketAddress instance, got: " + socketAddress);
        InetSocketAddress inetSocketAddress = (InetSocketAddress)socketAddress;
        InetAddress address = inetSocketAddress.getAddress();
        if (address instanceof Inet4Address)
        {
            MemorySegment sin = sockaddr_in.allocate(scope);
            sockaddr_in.set_sin_family(sin, AF_INET);
            sockaddr_in.set_sin_port(sin, (short)inetSocketAddress.getPort());
            sockaddr_in.set_sin_addr(sin, ByteBuffer.wrap(address.getAddress()).getInt());
            return sin;
        }
        else if (address instanceof Inet6Address)
        {
            MemorySegment sin6 = sockaddr_in6.allocate(scope);
            sockaddr_in6.set_sin6_family(sin6, AF_INET6);
            sockaddr_in6.set_sin6_port(sin6, (short)inetSocketAddress.getPort());
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

    private static class sockaddr_in
    {
        private static final MemoryLayout LAYOUT = MemoryLayout.structLayout(
            C_SHORT.withName("sin_family"),
            C_SHORT.withName("sin_port"),
            C_INT.withName("sin_addr"),
            MemoryLayout.sequenceLayout(8, C_CHAR).withName("sin_zero")
        ).withName("sockaddr_in");

        private static final VarHandle sin_family = LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("sin_family"));
        private static final VarHandle sin_port = LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("sin_port"));
        private static final VarHandle sin_addr = LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("sin_addr"));

        public static MemorySegment allocate(SegmentAllocator scope)
        {
            return scope.allocate(LAYOUT);
        }

        public static void set_sin_family(MemorySegment sin, short value)
        {
            sin_family.set(sin, 0L, value);
        }

        public static void set_sin_port(MemorySegment sin, short value)
        {
            sin_port.set(sin, 0L, value);
        }

        public static void set_sin_addr(MemorySegment sin, int value)
        {
            sin_addr.set(sin, 0L, value);
        }
    }

    private static class sockaddr_in6
    {
        private static final MemoryLayout LAYOUT = MemoryLayout.structLayout(
            C_SHORT.withName("sin6_family"),
            C_SHORT.withName("sin6_port"),
            C_INT.withName("sin6_flowinfo"),
            MemoryLayout.sequenceLayout(16, C_CHAR).withName("sin6_addr"),
            C_INT.withName("sin6_scope_id")
        ).withName("sockaddr_in6");

        private static final VarHandle sin6_family = LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("sin6_family"));
        private static final VarHandle sin6_port = LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("sin6_port"));
        private static final VarHandle sin6_scope_id = LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("sin6_scope_id"));
        private static final VarHandle sin6_flowinfo = LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("sin6_flowinfo"));

        public static MemorySegment allocate(SegmentAllocator scope)
        {
            return scope.allocate(LAYOUT);
        }

        public static void set_sin6_addr(MemorySegment sin6, byte[] value)
        {
            sin6.asSlice(8, 16).asByteBuffer().order(ByteOrder.nativeOrder()).put(value);
        }

        public static void set_sin6_family(MemorySegment sin6, short value)
        {
            sin6_family.set(sin6, 0L, value);
        }

        public static void set_sin6_port(MemorySegment sin6, short value)
        {
            sin6_port.set(sin6, 0L, value);
        }

        public static void set_sin6_scope_id(MemorySegment sin6, int value)
        {
            sin6_scope_id.set(sin6, 0L, value);
        }

        public static void set_sin6_flowinfo(MemorySegment sin6, int value)
        {
            sin6_flowinfo.set(sin6, 0L, value);
        }
    }
}
