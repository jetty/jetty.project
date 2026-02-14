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

package org.eclipse.jetty.quic.common.tls;

import org.eclipse.jetty.quic.api.QuicVersion;
import org.eclipse.jetty.quic.api.frames.TransportParameters;
import org.eclipse.jetty.tls.CipherSuite;
import org.eclipse.jetty.tls.TLSVersion;

public record HandshakeData(
    QuicVersion quicVersion,
    TLSVersion tlsVersion,
    String serverName,
    CipherSuite cipherSuite,
    String applicationProtocol,
    TransportParameters transportParameters
)
{
}
