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

import javax.crypto.SecretKey;

import org.eclipse.jetty.quic.api.frames.TransportParameters;
import org.eclipse.jetty.tls.CipherSuite;
import org.eclipse.jetty.tls.NewSessionTicketMessage;

/// A store for TLS session data used for ZeroRTT communication with a server.
///
/// Typically only clients need this data structure, as servers are typically
/// implemented in a stateless way: all the information is stored in the
/// TLS session ticket.
/// However, servers can also be implemented in a stateful way, where the
/// TLS session ticket is the key to access this data structure.
public class ZeroRTTStore
{
    // The information necessary for ZeroRTT communication.
    public record Entry(SecretKey resumptionMasterSecret, NewSessionTicketMessage newSessionTicket, CipherSuite cipherSuite,
                        String alpnProtocol, TransportParameters transportParameters)
    {
    }
}
