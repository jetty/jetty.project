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

package org.eclipse.jetty.tests.distribution.siwe;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class  SignInWithEthereumGenerator
{
    private SignInWithEthereumGenerator()
    {
    }

    public static String generateMessage(int port, String address, String nonce)
    {
        return generateMessage(null, "localhost:" + port, address, nonce, null, null);
    }

    public static String generateMessage(String scheme, String domain, String address, String nonce)
    {
        return generateMessage(scheme, domain, address, nonce, null, null);
    }

    public static String generateMessage(String scheme, String domain, String address, String nonce, String chainId)
    {
        return generateMessage(scheme,
            domain,
            address,
            "I accept the MetaMask Terms of Service: https://community.metamask.io/tos",
            "http://" + domain,
            "1",
            chainId,
            nonce,
            LocalDateTime.now(),
            null,
            null,
            null,
            null);
    }

    public static String generateMessage(String scheme, String domain, String address, String nonce, LocalDateTime expiresAt, LocalDateTime notBefore)
    {
        return generateMessage(scheme,
            domain,
            address,
            "I accept the MetaMask Terms of Service: https://community.metamask.io/tos",
            "http://" + domain,
            "1",
            "1",
            nonce,
            LocalDateTime.now(),
            expiresAt,
            notBefore,
            null,
            null);
    }

    public static String generateMessage(String scheme,
                                         String domain,
                                         String address,
                                         String statement,
                                         String uri,
                                         String version,
                                         String chainId,
                                         String nonce,
                                         LocalDateTime issuedAt,
                                         LocalDateTime expirationTime,
                                         LocalDateTime notBefore,
                                         String requestId,
                                         String resources)
    {
        StringBuilder sb = new StringBuilder();
        if (scheme != null)
            sb.append(scheme).append("://");
        sb.append(domain).append(" wants you to sign in with your Ethereum account:\n");
        sb.append(address).append("\n\n");
        sb.append(statement).append("\n\n");
        sb.append("URI: ").append(uri).append("\n");
        sb.append("Version: ").append(version).append("\n");
        sb.append("Chain ID: ").append(chainId).append("\n");
        sb.append("Nonce: ").append(nonce).append("\n");
        sb.append("Issued At: ").append(issuedAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        if (expirationTime != null)
            sb.append("\nExpiration Time: ").append(expirationTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        if (notBefore != null)
            sb.append("\nNot Before: ").append(notBefore.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        if (requestId != null)
            sb.append("\nRequest ID: ").append(requestId);
        if (resources != null)
            sb.append("\nResources:").append(resources);
        return sb.toString();
    }
}
