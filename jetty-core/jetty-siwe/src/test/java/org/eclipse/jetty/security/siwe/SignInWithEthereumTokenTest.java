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

package org.eclipse.jetty.security.siwe;

import java.time.LocalDateTime;
import java.util.function.Predicate;

import org.eclipse.jetty.security.siwe.internal.EthereumUtil;
import org.eclipse.jetty.security.siwe.internal.SignInWithEthereumParser;
import org.eclipse.jetty.security.siwe.internal.SignInWithEthereumToken;
import org.eclipse.jetty.security.siwe.util.EthereumCredentials;
import org.eclipse.jetty.security.siwe.util.SignInWithEthereumGenerator;
import org.eclipse.jetty.util.IncludeExcludeSet;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class SignInWithEthereumTokenTest
{
    @Test
    public void testInvalidVersion() throws Exception
    {
        EthereumCredentials credentials = new EthereumCredentials();
        LocalDateTime issuedAt = LocalDateTime.now();
        String message = SignInWithEthereumGenerator.generateMessage(
            null,
            "example.com",
            credentials.getAddress(),
            "hello this is the statement",
            "https://example.com",
            "2",
            "1",
            EthereumUtil.createNonce(),
            issuedAt,
            null, null, null, null
        );

        SignedMessage signedMessage = credentials.signMessage(message);
        SignInWithEthereumToken siwe = SignInWithEthereumParser.parse(message);
        assertNotNull(siwe);

        Throwable error = assertThrows(Throwable.class, () ->
            siwe.validate(signedMessage, null, null, null));
        assertThat(error.getMessage(), containsString("unsupported version"));
    }

    @Test
    public void testExpirationTime() throws Exception
    {
        EthereumCredentials credentials = new EthereumCredentials();
        LocalDateTime issuedAt = LocalDateTime.now().minusSeconds(10);
        LocalDateTime expiry = LocalDateTime.now();
        String message = SignInWithEthereumGenerator.generateMessage(
            null,
            "example.com",
            credentials.getAddress(),
            "hello this is the statement",
            "https://example.com",
            "1",
            "1",
            EthereumUtil.createNonce(),
            issuedAt,
            expiry,
            null, null, null
        );

        SignedMessage signedMessage = credentials.signMessage(message);
        SignInWithEthereumToken siwe = SignInWithEthereumParser.parse(message);
        assertNotNull(siwe);

        Throwable error = assertThrows(Throwable.class, () ->
            siwe.validate(signedMessage, null, null, null));
        assertThat(error.getMessage(), containsString("expired SIWE message"));
    }

    @Test
    public void testNotBefore() throws Exception
    {
        EthereumCredentials credentials = new EthereumCredentials();
        LocalDateTime issuedAt = LocalDateTime.now();
        LocalDateTime notBefore = issuedAt.plusMinutes(10);
        String message = SignInWithEthereumGenerator.generateMessage(
            null,
            "example.com",
            credentials.getAddress(),
            "hello this is the statement",
            "https://example.com",
            "1",
            "1",
            EthereumUtil.createNonce(),
            issuedAt,
            null,
            notBefore,
            null, null
        );

        SignedMessage signedMessage = credentials.signMessage(message);
        SignInWithEthereumToken siwe = SignInWithEthereumParser.parse(message);
        assertNotNull(siwe);

        Throwable error = assertThrows(Throwable.class, () ->
            siwe.validate(signedMessage, null, null, null));
        assertThat(error.getMessage(), containsString("SIWE message not yet valid"));
    }

    @Test
    public void testInvalidDomain() throws Exception
    {
        EthereumCredentials credentials = new EthereumCredentials();
        LocalDateTime issuedAt = LocalDateTime.now();
        String message = SignInWithEthereumGenerator.generateMessage(
            null,
            "example.com",
            credentials.getAddress(),
            "hello this is the statement",
            "https://example.com",
            "1",
            "1",
            EthereumUtil.createNonce(),
            issuedAt,
            null, null, null, null
        );

        SignedMessage signedMessage = credentials.signMessage(message);
        SignInWithEthereumToken siwe = SignInWithEthereumParser.parse(message);
        assertNotNull(siwe);

        IncludeExcludeSet<String, String> domains = new IncludeExcludeSet<>();
        domains.include("example.org");

        Throwable error = assertThrows(Throwable.class, () ->
            siwe.validate(signedMessage, null, domains, null));
        assertThat(error.getMessage(), containsString("unregistered domain"));
    }

    @Test
    public void testInvalidChainId() throws Exception
    {
        EthereumCredentials credentials = new EthereumCredentials();
        LocalDateTime issuedAt = LocalDateTime.now();
        String message = SignInWithEthereumGenerator.generateMessage(
            "https",
            "example.com",
            credentials.getAddress(),
            "hello this is the statement",
            "https://example.com",
            "1",
            "1",
            EthereumUtil.createNonce(),
            issuedAt,
            null, null, null, null
        );

        SignedMessage signedMessage = credentials.signMessage(message);
        SignInWithEthereumToken siwe = SignInWithEthereumParser.parse(message);
        assertNotNull(siwe);

        IncludeExcludeSet<String, String> chainIds = new IncludeExcludeSet<>();
        chainIds.include("1337");

        Throwable error = assertThrows(Throwable.class, () ->
            siwe.validate(signedMessage, null, null, chainIds));
        assertThat(error.getMessage(), containsString("unregistered chainId"));
    }

    @Test
    public void testInvalidNonce() throws Exception
    {
        EthereumCredentials credentials = new EthereumCredentials();
        LocalDateTime issuedAt = LocalDateTime.now();
        String message = SignInWithEthereumGenerator.generateMessage(
            "https",
            "example.com",
            credentials.getAddress(),
            "hello this is the statement",
            "https://example.com",
            "1",
            "1",
            EthereumUtil.createNonce(),
            issuedAt,
            null, null, null, null
        );

        SignedMessage signedMessage = credentials.signMessage(message);
        SignInWithEthereumToken siwe = SignInWithEthereumParser.parse(message);
        assertNotNull(siwe);

        Predicate<String> nonceValidation = nonce -> false;
        Throwable error = assertThrows(Throwable.class, () ->
            siwe.validate(signedMessage, nonceValidation, null, null));
        assertThat(error.getMessage(), containsString("invalid nonce"));
    }

    @Test
    public void testValidToken() throws Exception
    {
        EthereumCredentials credentials = new EthereumCredentials();
        LocalDateTime issuedAt = LocalDateTime.now();
        String message = SignInWithEthereumGenerator.generateMessage(
            "https",
            "example.com",
            credentials.getAddress(),
            "hello this is the statement",
            "https://example.com",
            "1",
            "1",
            EthereumUtil.createNonce(),
            issuedAt,
            null, null, null, null
        );

        SignedMessage signedMessage = credentials.signMessage(message);
        SignInWithEthereumToken siwe = SignInWithEthereumParser.parse(message);
        assertNotNull(siwe);

        Predicate<String> nonceValidation = nonce -> true;
        assertDoesNotThrow(() ->
            siwe.validate(signedMessage, nonceValidation, null, null));
    }
}
