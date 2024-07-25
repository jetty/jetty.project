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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.eclipse.jetty.security.siwe.internal.EthereumUtil;
import org.eclipse.jetty.security.siwe.internal.SignInWithEthereumToken;
import org.eclipse.jetty.security.siwe.util.SignInWithEthereumGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class SignInWithEthereumParserTest
{
    public static Stream<Arguments> specExamples()
    {
        List<Arguments> data = new ArrayList<>();

        data.add(Arguments.of("""
            example.com wants you to sign in with your Ethereum account:
            0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2

            I accept the ExampleOrg Terms of Service: https://example.com/tos

            URI: https://example.com/login
            Version: 1
            Chain ID: 1
            Nonce: 32891756
            Issued At: 2021-09-30T16:25:24Z
            Resources:
            - ipfs://bafybeiemxf5abjwjbikoz4mc3a3dla6ual3jsgpdr4cjr3oz3evfyavhwq/
            - https://example.com/my-web2-claim.json""",
            null, "example.com"
            ));


        data.add(Arguments.of("""
                example.com:3388 wants you to sign in with your Ethereum account:
                0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2
                
                I accept the ExampleOrg Terms of Service: https://example.com/tos
                
                URI: https://example.com/login
                Version: 1
                Chain ID: 1
                Nonce: 32891756
                Issued At: 2021-09-30T16:25:24Z
                Resources:
                - ipfs://bafybeiemxf5abjwjbikoz4mc3a3dla6ual3jsgpdr4cjr3oz3evfyavhwq/
                - https://example.com/my-web2-claim.json""",
            null, "example.com:3388"
        ));

        data.add(Arguments.of("""
                https://example.com wants you to sign in with your Ethereum account:
                0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2
                
                I accept the ExampleOrg Terms of Service: https://example.com/tos
                
                URI: https://example.com/login
                Version: 1
                Chain ID: 1
                Nonce: 32891756
                Issued At: 2021-09-30T16:25:24Z
                Resources:
                - ipfs://bafybeiemxf5abjwjbikoz4mc3a3dla6ual3jsgpdr4cjr3oz3evfyavhwq/
                - https://example.com/my-web2-claim.json""",
            "https", "example.com"
        ));

        return data.stream();
    }

    @ParameterizedTest
    @MethodSource("specExamples")
    public void testSpecExamples(String message, String scheme, String domain)
    {
        SignInWithEthereumToken siwe = SignInWithEthereumToken.from(message);
        assertNotNull(siwe);
        assertThat(siwe.address(), equalTo("0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2"));
        assertThat(siwe.issuedAt(), equalTo("2021-09-30T16:25:24Z"));
        assertThat(siwe.uri(), equalTo("https://example.com/login"));
        assertThat(siwe.version(), equalTo("1"));
        assertThat(siwe.chainId(), equalTo("1"));
        assertThat(siwe.nonce(), equalTo("32891756"));
        assertThat(siwe.statement(), equalTo("I accept the ExampleOrg Terms of Service: https://example.com/tos"));
        assertThat(siwe.scheme(), equalTo(scheme));
        assertThat(siwe.domain(), equalTo(domain));

        String resources = """
            
            - ipfs://bafybeiemxf5abjwjbikoz4mc3a3dla6ual3jsgpdr4cjr3oz3evfyavhwq/
            - https://example.com/my-web2-claim.json""";
        assertThat(siwe.resources(), equalTo(resources));
    }

    @Test
    public void testFullMessage()
    {
        String scheme = "http";
        String domain = "example.com";
        String address = "0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2";
        String statement = "This is the statement asking you to sign in.";
        String uri = "https://example.com/login";
        String version = "1";
        String chainId = "1";
        String nonce = EthereumUtil.createNonce();
        LocalDateTime issuedAt = LocalDateTime.now();
        LocalDateTime expirationTime = LocalDateTime.now().plusDays(1);
        LocalDateTime notBefore = LocalDateTime.now().minusDays(1);
        String requestId = "123456789";
        String resources = """
            
            - ipfs://bafybeiemxf5abjwjbikoz4mc3a3dla6ual3jsgpdr4cjr3oz3evfyavhwq/
            - https://example.com/my-web2-claim.json""";

        String message = SignInWithEthereumGenerator.generateMessage(scheme, domain, address, statement, uri, version, chainId, nonce, issuedAt,
            expirationTime, notBefore, requestId, resources);

        SignInWithEthereumToken siwe = SignInWithEthereumToken.from(message);
        assertNotNull(siwe);
        assertThat(siwe.scheme(), equalTo(scheme));
        assertThat(siwe.domain(), equalTo(domain));
        assertThat(siwe.address(), equalTo(address));
        assertThat(siwe.statement(), equalTo(statement));
        assertThat(siwe.uri(), equalTo(uri));
        assertThat(siwe.version(), equalTo(version));
        assertThat(siwe.chainId(), equalTo(chainId));
        assertThat(siwe.nonce(), equalTo(nonce));
        assertThat(siwe.issuedAt(), equalTo(issuedAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)));
        assertThat(siwe.expirationTime(), equalTo(expirationTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)));
        assertThat(siwe.notBefore(), equalTo(notBefore.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)));
        assertThat(siwe.requestId(), equalTo(requestId));
        assertThat(siwe.resources(), equalTo(resources));
    }
}
