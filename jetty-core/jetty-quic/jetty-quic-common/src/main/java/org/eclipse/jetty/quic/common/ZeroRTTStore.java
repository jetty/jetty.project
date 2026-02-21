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

import java.util.function.Predicate;
import javax.crypto.SecretKey;

import org.eclipse.jetty.quic.common.tls.HandshakeData;
import org.eclipse.jetty.tls.NewSessionTicketMessage;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.TypeUtil;

/// A store for TLS session data used for ZeroRTT communication with a server.
///
/// Typically only clients need this data structure, as servers are typically
/// implemented in a stateless way: all the information is stored in the
/// TLS session ticket.
/// However, servers can also be implemented in a stateful way, where the
/// TLS session ticket is the key to access this data structure.
public interface ZeroRTTStore
{
    default void store(Entry entry)
    {
    }

    default Entry match(Predicate<Entry> filter)
    {
        return null;
    }

    default int size()
    {
        return 0;
    }

    final class Entry
    {
        private final long nanoTime;
        private final HandshakeData handshakeData;
        private final NewSessionTicketMessage newSessionTicket;
        private final SecretKey secretKey;

        public Entry(HandshakeData handshakeData, NewSessionTicketMessage newSessionTicket, SecretKey secretKey)
        {
            this.nanoTime = NanoTime.now();
            this.handshakeData = handshakeData;
            this.newSessionTicket = newSessionTicket;
            this.secretKey = secretKey;
        }

        public HandshakeData handshakeData()
        {
            return handshakeData;
        }

        public NewSessionTicketMessage newSessionTicket()
        {
            return newSessionTicket;
        }

        public int obfuscatedTicketAge()
        {
            // RFC-8446[4.2.11.1]: cast to int is equivalent to mod 2^32.
            return (int)(NanoTime.millisSince(nanoTime) + newSessionTicket().ageAdd());
        }

        public SecretKey secretKey()
        {
            return secretKey;
        }

        public boolean expired()
        {
            return NanoTime.millisSince(nanoTime) > newSessionTicket().lifetime();
        }

        @Override
        public String toString()
        {
            return "%s@%x".formatted(TypeUtil.toShortName(getClass()), hashCode());
        }
    }
}
