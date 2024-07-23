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
import org.eclipse.jetty.security.siwe.EthereumAuthenticator;
import org.eclipse.jetty.util.IncludeExcludeSet;
import org.eclipse.jetty.util.StringUtil;

/**
 * Record representing a parsed SIWE message defined by <a href="https://eips.ethereum.org/EIPS/eip-4361">EIP4361</a>.
 * @param scheme the URI scheme of the origin of the request.
 * @param domain the domain that is requesting the signing.
 * @param address the Ethereum address performing the signing.
 * @param statement a human-readable ASCII assertion that the user will sign.
 * @param uri an RFC 3986 URI referring to the resource that is the subject of the signing.
 * @param version the version of the SIWE Message.
 * @param chainId the Chain ID to which the session is bound.
 * @param nonce a random string used to prevent replay attacks.
 * @param issuedAt time when the message was generated.
 * @param expirationTime time when the signed authentication message is no longer valid.
 * @param notBefore time when the signed authentication message will become valid.
 * @param requestId a system-specific request identifier.
 * @param resources list of resources the user wishes to have resolved as part of authentication.
 */
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

    /**
     * @param signedMessage the {@link EthereumAuthenticator.SignedMessage}.
     * @param validateNonce a {@link Predicate} used to validate the nonce.
     * @param domains the {@link IncludeExcludeSet} used to validate the domain.
     * @param chainIds the {@link IncludeExcludeSet} used to validate the chainId.
     * @throws ServerAuthException if the {@link EthereumAuthenticator.SignedMessage} fails validation.
     */
    public void validate(EthereumAuthenticator.SignedMessage signedMessage, Predicate<String> validateNonce,
                         IncludeExcludeSet<String, String> domains,
                         IncludeExcludeSet<String, String> chainIds) throws ServerAuthException
    {
        if (validateNonce != null && !validateNonce.test(nonce()))
            throw new ServerAuthException("invalid nonce " + nonce);

        if (!StringUtil.asciiEqualsIgnoreCase(signedMessage.recoverAddress(), address()))
            throw new ServerAuthException("signature verification failed");

        if (!"1".equals(version()))
            throw new ServerAuthException("unsupported version " + version);

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

        if (domains != null && !domains.test(domain()))
            throw new ServerAuthException("unregistered domain: " + domain());
        if (chainIds != null && !chainIds.test(chainId()))
            throw new ServerAuthException("unregistered chainId: " + chainId());
    }
}
