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

package org.eclipse.jetty.tls.common;

import org.eclipse.jetty.tls.CipherSuite;

/// The Transcript Hash defined in
/// [RFC 8446, 4.4.1](https://datatracker.ietf.org/doc/html/rfc8446#section-4.4.1).
///
/// The Transcript Hash keeps a running hash of TLS handshake messages
/// that have been sent, providing a hash used to derive secrets.
public class TranscriptHash
{
    private final CipherSuite cipherSuite;

    public TranscriptHash(CipherSuite cipherSuite)
    {
        this.cipherSuite = cipherSuite;
    }
}
