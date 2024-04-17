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

package org.eclipse.jetty.quic.quiche.foreign;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.net.SocketAddress;

import org.eclipse.jetty.quic.quiche.foreign.linux.sockaddr_linux;
import org.eclipse.jetty.quic.quiche.foreign.macos.sockaddr_macos;
import org.eclipse.jetty.quic.quiche.foreign.windows.sockaddr_windows;

public class sockaddr
{
    public static MemorySegment convert(SocketAddress socketAddress, SegmentAllocator allocator)
    {
        if (NativeHelper.isLinux())
            return sockaddr_linux.convert(socketAddress, allocator);
        if (NativeHelper.isMac())
            return sockaddr_macos.convert(socketAddress, allocator);
        if (NativeHelper.isWindows())
            return sockaddr_windows.convert(socketAddress, allocator);
        throw new UnsupportedOperationException("Unsupported OS: " + System.getProperty("os.name"));
    }
}
