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

import java.net.SocketAddress;

import jdk.incubator.foreign.MemorySegment;
import jdk.incubator.foreign.ResourceScope;
import org.eclipse.jetty.quic.quiche.panama.jdk.linux.sockaddr_linux;
import org.eclipse.jetty.quic.quiche.panama.jdk.macos.sockaddr_macos;
import org.eclipse.jetty.quic.quiche.panama.jdk.windows.sockaddr_windows;

import static org.eclipse.jetty.quic.quiche.panama.jdk.NativeHelper.isLinux;
import static org.eclipse.jetty.quic.quiche.panama.jdk.NativeHelper.isMac;
import static org.eclipse.jetty.quic.quiche.panama.jdk.NativeHelper.isWindows;

public class sockaddr
{
    public static MemorySegment convert(SocketAddress socketAddress, ResourceScope scope)
    {
        if (isLinux())
            return sockaddr_linux.convert(socketAddress, scope);
        if (isMac())
            return sockaddr_macos.convert(socketAddress, scope);
        if (isWindows())
            return sockaddr_windows.convert(socketAddress, scope);
        throw new UnsupportedOperationException("Unsupported OS: " + System.getProperty("os.name"));
    }
}
