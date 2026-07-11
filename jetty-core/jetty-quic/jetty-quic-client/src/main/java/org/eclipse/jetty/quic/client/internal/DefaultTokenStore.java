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

package org.eclipse.jetty.quic.client.internal;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.eclipse.jetty.quic.client.TokenStore;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// A default implementation of [TokenStore].
///
/// Tokens are stored keyed by the server name, if available,
/// or by stringifying the server socket address.
///
/// The server is then responsible for validating the token,
/// checking for expiration, and whether the current client
/// socket address matches the client socket address at the
/// time the token was issued.
public class DefaultTokenStore implements TokenStore
{
    private static final Logger LOG = LoggerFactory.getLogger(DefaultTokenStore.class);

    private final Map<String, Queue<byte[]>> allTokens = new ConcurrentHashMap<>();

    public byte[] retrieve(SocketAddress clientSocketAddress, SocketAddress serverSocketAddress)
    {
        String key = toKey(serverSocketAddress);
        Queue<byte[]> tokens = allTokens.get(key);
        byte[] token = tokens == null || tokens.isEmpty() ? null : tokens.poll();
        if (LOG.isDebugEnabled())
            LOG.debug("retrieved token {} for {} on {}", token == null ? "null" : StringUtil.toHexString(token), key, this);
        return token;
    }

    public void store(SocketAddress clientSocketAddress, SocketAddress serverSocketAddress, byte[] token)
    {
        String key = toKey(serverSocketAddress);
        allTokens.computeIfAbsent(key, k -> new ConcurrentLinkedQueue<>()).offer(Objects.requireNonNull(token));
        if (LOG.isDebugEnabled())
            LOG.debug("stored token {} for {} on {}", StringUtil.toHexString(token), key, this);
    }

    public int size()
    {
        return allTokens.size();
    }

    private String toKey(SocketAddress serverSocketAddress)
    {
        if (serverSocketAddress instanceof InetSocketAddress serverInet)
            return serverInet.getHostString();
        else
            return serverSocketAddress.toString();
    }

    @Override
    public String toString()
    {
        return "%s@%x".formatted(TypeUtil.toShortName(getClass()), hashCode());
    }
}
