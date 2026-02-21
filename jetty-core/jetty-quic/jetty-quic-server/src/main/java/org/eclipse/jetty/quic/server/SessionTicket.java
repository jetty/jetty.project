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

import java.time.Duration;
import javax.crypto.SecretKey;

import org.eclipse.jetty.quic.common.tls.HandshakeData;
import org.eclipse.jetty.tls.NewSessionTicketMessage;
import org.eclipse.jetty.tls.ext.ClientPreSharedKeyExtension;

/// The session ticket sent as part of [NewSessionTicketMessage]s.
///
/// Session tickets are sent by the server when the TLS handshake is successful.
/// The client stores them and uses them to establish a _resumed_ connection.
/// Resumed connections are useful as they save bandwidth (server does not need
/// to send certificate to the client), and CPU (key generation, certificate
/// verifications, etc.).
///
/// A resumed connection may also send early data, and this combination is
/// called _ZeroRTT_, since data can be sent before the first round trip.
///
/// ZeroRTT may suffer from replay attacks, where the `ClientHello` and the
/// early data are replayed.
/// TLS and QUIC do not, per-se, suffer from replay attacks: a replay attack
/// would just open another connection.
/// However, protocols on top of TLS or QUIC may suffer from replay attacks:
/// HTTP would suffer if the early data is not idempotent; for example, when
/// the early data is `POST /transfer?amount=100`.
///
/// While replay attack protection seems a responsibility of the upper layer
/// protocol, it is much more easily implemented in the lower layer.
///
/// When a client wants to resume a new connection or perform ZeroRTT, it
/// sends the [ClientPreSharedKeyExtension] containing the session ticket
/// sent by the server.
///
/// The server may process session tickets received by the client in two
/// main ways:
///
/// * The _stateful_ server case, where the session ticket is just a key
/// into a storage, where all the information to resume the connection
/// is stored.
/// In this case the server is stateful because it stores the resumption
/// information; statefulness comes with issues: keep the store bounded
/// in size, distribute the store in case of multiple servers, and so on.
/// * The _stateless_ server case, where the session ticket itself
/// contains all the information needed to resume the connection.
/// In this case, the server can be stateless.
///
/// To implement replay protection, the server must be stateful.
/// Even in the case of stateless session tickets, the server must be able
/// to determine whether it is a replay, and to do so it must store some
/// state.
/// A typical implementation could be to have ticket sequence numbers,
/// keep track of the largest sequence number, and discard session tickets
/// with smaller sequence numbers.
///
/// [SessionTicket.Factory] support both the stateful and stateless session
/// ticket cases, as both cases are used in real deployments.
///
/// Stateless [SessionTicket.Factory] implementations cover the stateless
/// session tickets case, allow connection resumption, require no server state,
/// and do not need to implement replay protection, as there is no early data.
/// They are also used when the upper layer protocol can ensure idempotent
/// operations.
///
/// Stateful [SessionTicket.Factory] implementations cover the stateful
/// session ticket case, allow ZeroRTT, and may implement replay protection.
public record SessionTicket(Configuration configuration, HandshakeData handshakeData, SecretKey resumptionMasterSecret)
{
    /// The configuration of a session ticket.
    ///
    /// NOTE: Do not convert this class to a record.
    /// Using a class allows its evolution, such as adding more getter methods.
    /// On the other hand, adding a field to a record would break backwards
    /// compatibility, for example when used in a record pattern match.
    public static class Configuration
    {
        private final int lifetime;
        private final int ageAdd;
        private final byte[] nonce;

        public Configuration(int lifetime, int ageAdd, byte[] nonce)
        {
            this.lifetime = lifetime;
            this.ageAdd = ageAdd;
            this.nonce = nonce;
        }

        public int lifetime()
        {
            return lifetime;
        }

        public int ageAdd()
        {
            return ageAdd;
        }

        public byte[] nonce()
        {
            return nonce;
        }
    }

    /// A factory for [SessionTicket]s.
    public interface Factory
    {
        /// @return the session ticket lifetime
        Duration getSessionTicketLifetime();

        /// Serializes a [SessionTicket] into a byte array.
        ///
        /// This is the opposite operation of [#parseSessionTicket(byte\[\])].
        ///
        /// @param ticket the ticket to serialize
        /// @return the bytes generated by serializing the given [SessionTicket],
        /// or `null` if the serialization fails.
        byte[] generateSessionTicket(SessionTicket ticket);

        /// Deserializes a byte array into a [SessionTicket].
        ///
        /// This is the opposite operation of [#generateSessionTicket(SessionTicket)].
        ///
        /// @param bytes the byte array to deserialize
        /// @return the [SessionTicket] generated by deserializing the given byte array,
        /// or `null` if the deserialization fails, or if the session ticket is expired.
        SessionTicket parseSessionTicket(byte[] bytes);
    }
}
