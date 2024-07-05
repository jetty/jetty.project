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

package org.eclipse.jetty.security.siwe.internal;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Predicate;

import org.eclipse.jetty.security.ServerAuthException;
import org.eclipse.jetty.security.siwe.SignedMessage;
import org.eclipse.jetty.util.IncludeExcludeSet;
import org.eclipse.jetty.util.StringUtil;

public record SignInWithEthereumToken(String scheme,
                                      String domain,
                                      String address,
                                      String statement,
                                      String uri,
                                      String version,
                                      String chainId,
                                      String nonce,
                                      String issuedAt,
                                      String expirationTime,
                                      String notBefore,
                                      String requestId,
                                      String resources)
{

    public void validate(SignedMessage signedMessage, Predicate<String> validateNonce,
                         IncludeExcludeSet<String, String> schemes,
                         IncludeExcludeSet<String, String> domains,
                         IncludeExcludeSet<String, String> chainIds) throws ServerAuthException
    {
        if (validateNonce != null && !validateNonce.test(nonce()))
            throw new ServerAuthException("invalid nonce");

        if (!StringUtil.asciiEqualsIgnoreCase(signedMessage.recoverAddress(), address()))
            throw new ServerAuthException("signature verification failed");

        if (!"1".equals(version()))
            throw new ServerAuthException("unsupported version");

        LocalDateTime now = LocalDateTime.now();
        if (StringUtil.isNotBlank(expirationTime()))
        {
            LocalDateTime expirationTime = LocalDateTime.parse(expirationTime(), DateTimeFormatter.ISO_DATE_TIME);
            if (now.isAfter(expirationTime))
                throw new ServerAuthException("expired SIWE message");
        }

        if (StringUtil.isNotBlank(notBefore()))
        {
            LocalDateTime notBefore = LocalDateTime.parse(notBefore(), DateTimeFormatter.ISO_DATE_TIME);
            if (now.isBefore(notBefore))
                throw new ServerAuthException("SIWE message not yet valid");
        }

        if (schemes != null && !schemes.test(scheme()))
            throw new ServerAuthException("unregistered scheme");
        if (domains != null && !domains.test(domain()))
            throw new ServerAuthException("unregistered domain");
        if (chainIds != null && !chainIds.test(chainId()))
            throw new ServerAuthException("unregistered chainId");
    }
}
