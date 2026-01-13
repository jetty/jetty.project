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

package org.eclipse.jetty.quic.common;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.quic.common.packets.RetryPacket;
import org.eclipse.jetty.util.BufferUtil;

/// Generates, stores, and yields tokens used for address validation.
///
/// A client that wants to connect to a specific server queries this
/// object to retrieve a token that was previously sent by a server
/// via a [RetryPacket].
///
/// Refer to RFC 9000, sections 8 and 17.2.5.
public class Tokens
{
    private final Map<Key, byte[]> tokens = new ConcurrentHashMap<>();

    public byte[] get(SocketAddress clientSocketAddress, SocketAddress serverSocketAddress)
    {
        byte[] token = tokens.get(toKey(clientSocketAddress, serverSocketAddress));
        return token != null ? token : BufferUtil.EMPTY_BYTES;
    }

    public void put(SocketAddress clientSocketAddress, SocketAddress serverSocketAddress, byte[] token)
    {
        tokens.put(toKey(clientSocketAddress, serverSocketAddress), token);
    }

    private Key toKey(SocketAddress clientSocketAddress, SocketAddress serverSocketAddress)
    {
        if (clientSocketAddress instanceof InetSocketAddress clientInet)
        {
            if (serverSocketAddress instanceof InetSocketAddress serverInet)
                return new Key(clientInet.getAddress().getHostAddress(), serverInet.getHostString());
            else
                throw new IllegalArgumentException("invalid server socket address: " + serverSocketAddress);
        }
        else
        {
            throw new IllegalArgumentException("invalid client socket address: " + clientSocketAddress);
        }
    }

    /// The key to use to store tokens.
    ///
    /// @param clientAddress the client IP address
    /// @param serverName the server host name
    private record Key(String clientAddress, String serverName)
    {
    }
}
