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

package org.eclipse.jetty.server;

import java.security.cert.X509Certificate;

/**
 * Simple bundle of data that is cached in the SSLSession.
 */
public record SslSessionData(String sessionId, String cipherSuite, Integer keySize, X509Certificate[] peerCertificates)
{
    public static final String ATTRIBUTE = SecureRequestCustomizer.SSL_SESSION_DATA_ATTRIBUTE;

    public static SslSessionData from(SslSessionData dftSslSessionData, String sessionId, String cipherSuite, Integer keySize, X509Certificate[] peerCertificates)
    {
        return (dftSslSessionData == null)
            ? new SslSessionData(sessionId, cipherSuite, keySize, peerCertificates)
            : new SslSessionData(
                sessionId != null ? sessionId : dftSslSessionData.sessionId,
                cipherSuite != null ? cipherSuite : dftSslSessionData.cipherSuite,
                keySize != null && keySize > 0 ? keySize : dftSslSessionData.keySize,
                peerCertificates != null ? peerCertificates : dftSslSessionData.peerCertificates());
    }
}
