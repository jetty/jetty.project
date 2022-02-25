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

package org.eclipse.jetty.quic.quiche.jna;

import java.net.SocketAddress;

import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import org.eclipse.jetty.quic.quiche.jna.linux.netinet_linux;
import org.eclipse.jetty.quic.quiche.jna.macos.netinet_macos;
import org.eclipse.jetty.quic.quiche.jna.windows.netinet_windows;

import static com.sun.jna.Platform.isLinux;
import static com.sun.jna.Platform.isMac;
import static com.sun.jna.Platform.isWindows;

@Structure.FieldOrder({"opaque"})
public class sockaddr extends Structure
{
    public byte[] opaque = new byte[16]; // 16 opaque bytes

    public sockaddr(Pointer p)
    {
        super(p);
        read();
    }

    public static SizedStructure<sockaddr> convert(SocketAddress socketAddress)
    {
        if (isLinux())
            return netinet_linux.to_sock_addr(socketAddress);
        if (isMac())
            return netinet_macos.to_sock_addr(socketAddress);
        if (isWindows())
            return netinet_windows.to_sock_addr(socketAddress);
        throw new UnsupportedOperationException("Unsupported OS: " + System.getProperty("os.name"));
    }

    public ByReference byReference()
    {
        return new ByReference(getPointer());
    }

    public static class ByReference extends sockaddr implements Structure.ByReference
    {
        private ByReference(Pointer p)
        {
            super(p);
        }
    }
}
