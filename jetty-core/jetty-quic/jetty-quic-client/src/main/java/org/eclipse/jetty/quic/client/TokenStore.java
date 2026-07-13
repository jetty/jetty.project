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

package org.eclipse.jetty.quic.client;

import java.net.SocketAddress;

import org.eclipse.jetty.quic.api.frames.NewTokenFrame;

/// Stores and yields tokens used for address validation.
///
/// A client that wants to connect to a specific server queries
/// this token store to retrieve a token that was previously
/// sent by a server via either a [NewTokenFrame].
///
/// Refer to RFC-9000 #8 and #17.2.5.
public interface TokenStore
{
    /// @param clientSocketAddress the client socket address.
    /// @param serverSocketAddress the server socket address.
    /// @return a token for the given client and server socket addresses,
    /// or `null` if no token is available.
    byte[] retrieve(SocketAddress clientSocketAddress, SocketAddress serverSocketAddress);

    /// @param clientSocketAddress the client socket address.
    /// @param serverSocketAddress the server socket address.
    /// @param token the token to store.
    /// Stores the given token for the given client and server socket addresses.
    void store(SocketAddress clientSocketAddress, SocketAddress serverSocketAddress, byte[] token);
}
