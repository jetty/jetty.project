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

package org.eclipse.jetty.quic.quiche.foreign.incubator;

import java.net.SocketAddress;

import jdk.incubator.foreign.MemorySegment;
import jdk.incubator.foreign.ResourceScope;
import org.eclipse.jetty.quic.quiche.foreign.incubator.linux.sockaddr_linux;
import org.eclipse.jetty.quic.quiche.foreign.incubator.macos.sockaddr_macos;
import org.eclipse.jetty.quic.quiche.foreign.incubator.windows.sockaddr_windows;

public class sockaddr
{
    public static MemorySegment convert(SocketAddress socketAddress, ResourceScope scope)
    {
        if (NativeHelper.isLinux())
            return sockaddr_linux.convert(socketAddress, scope);
        if (NativeHelper.isMac())
            return sockaddr_macos.convert(socketAddress, scope);
        if (NativeHelper.isWindows())
            return sockaddr_windows.convert(socketAddress, scope);
        throw new UnsupportedOperationException("Unsupported OS: " + System.getProperty("os.name"));
    }
}
