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

package org.eclipse.jetty.quic.server;

import java.net.SocketAddress;

import org.eclipse.jetty.quic.api.frames.NewTokenFrame;
import org.eclipse.jetty.quic.common.packets.InitialPacket;
import org.eclipse.jetty.quic.common.packets.RetryPacket;

/// Server-side token creation and validation for [InitialPacket]s and for [NewTokenFrame]s.
///
/// Retry tokens must not be cached, have a short lifetime, and should only
/// be used for the current connection.
///
/// On the other hand, normal tokens sent in [NewTokenFrame]s should be cached,
/// have a longer lifetime, and can be used for multiple connections.
///
/// A retry token validates an unknown client; once validated, normal tokens
/// can be used to skip the [RetryPacket] round trip.
/// If the client migrates to a different network endpoint, normal tokens
/// for the previous network endpoint are not valid, and a [RetryPacket] is
/// required for the new connections, until the server sends normal tokens.
public interface TokenFactory
{
    /// Creates a new token to be used in [RetryPacket]s.
    ///
    /// @param remoteAddress the client socket address
    /// @param originalDestinationConnectionId the original destination connection id
    /// sent by the client in the first [InitialPacket]
    byte[] newRetryToken(SocketAddress remoteAddress, byte[] originalDestinationConnectionId) throws Exception;

    /// Creates a new token to be used in [NewTokenFrame]s.
    ///
    /// @param remoteAddress the client socket address
    byte[] newToken(SocketAddress remoteAddress) throws Exception;

    /// Validates a retry token received in a [RetryPacket].
    ///
    /// @param remoteAddress the client socket address
    /// @param originalDestinationConnectionId the original destination connection id
    /// sent by the client in the first [InitialPacket]
    /// @param retryToken the retry token received in a [RetryPacket]
    boolean isTokenValid(SocketAddress remoteAddress, byte[] originalDestinationConnectionId, byte[] retryToken);
}
