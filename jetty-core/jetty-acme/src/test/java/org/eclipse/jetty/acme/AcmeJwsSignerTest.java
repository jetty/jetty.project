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

package org.eclipse.jetty.acme;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

import org.eclipse.jetty.util.ajax.JSON;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

public class AcmeJwsSignerTest
{
    private KeyPair keyPair;
    private JSON json;
    private AcmeJwsSigner signer;

    @BeforeEach
    public void setUp() throws Exception
    {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048, new SecureRandom());
        keyPair = keyGen.generateKeyPair();

        json = new JSON();
        signer = new AcmeJwsSigner(keyPair, json);
    }

    @Test
    public void testGetJwk()
    {
        Map<String, Object> jwk = signer.getJwk();

        assertThat(jwk, hasKey("kty"));
        assertThat(jwk.get("kty"), equalTo("RSA"));
        assertThat(jwk, hasKey("n"));
        assertThat(jwk, hasKey("e"));
    }

    @Test
    public void testComputeThumbprint() throws Exception
    {
        String thumbprint = signer.computeThumbprint();

        assertThat(thumbprint, is(notNullValue()));
        assertThat(thumbprint.length(), is(43));
        // Base64url should not have + or /
        assertThat(thumbprint, not(containsString("+")));
        assertThat(thumbprint, not(containsString("/")));
        assertThat(thumbprint, not(containsString("=")));
    }

    @Test
    public void testComputeKeyAuthorization() throws Exception
    {
        String token = "test-token-abc123";
        String keyAuth = signer.computeKeyAuthorization(token);

        assertThat(keyAuth, is(notNullValue()));
        assertThat(keyAuth.startsWith(token + "."), is(true));
    }

    @Test
    public void testSignWithJwk() throws Exception
    {
        Map<String, Object> payload = Map.of("test", "value");
        String nonce = "test-nonce-12345";
        String url = "https://acme.example.com/test";

        String jwsJson = signer.sign(payload, nonce, url);

        assertThat(jwsJson, is(notNullValue()));

        // Parse the JWS
        @SuppressWarnings("unchecked")
        Map<String, String> jws = (Map<String, String>)json.fromJSON(jwsJson);

        assertThat(jws, hasKey("protected"));
        assertThat(jws, hasKey("payload"));
        assertThat(jws, hasKey("signature"));

        // Decode protected header
        String protectedB64 = jws.get("protected");
        String protectedJson = new String(Base64.getUrlDecoder().decode(protectedB64));
        @SuppressWarnings("unchecked")
        Map<String, Object> header = (Map<String, Object>)json.fromJSON(protectedJson);

        assertThat(header.get("alg"), equalTo("RS256"));
        assertThat(header.get("nonce"), equalTo(nonce));
        assertThat(header.get("url"), equalTo(url));
        assertThat(header, hasKey("jwk"));
        // No kid when account URL is not set
        assertThat(header.get("kid"), is(nullValue()));
    }

    @Test
    public void testSignWithKid() throws Exception
    {
        String accountUrl = "https://acme.example.com/acct/12345";
        signer.setAccountUrl(accountUrl);

        Map<String, Object> payload = Map.of("test", "value");
        String nonce = "test-nonce-67890";
        String url = "https://acme.example.com/test";

        String jwsJson = signer.sign(payload, nonce, url);

        // Parse the JWS
        @SuppressWarnings("unchecked")
        Map<String, String> jws = (Map<String, String>)json.fromJSON(jwsJson);

        // Decode protected header
        String protectedB64 = jws.get("protected");
        String protectedJson = new String(Base64.getUrlDecoder().decode(protectedB64));
        @SuppressWarnings("unchecked")
        Map<String, Object> header = (Map<String, Object>)json.fromJSON(protectedJson);

        assertThat(header.get("kid"), equalTo(accountUrl));
        // No jwk when kid is set
        assertThat(header.get("jwk"), is(nullValue()));
    }

    @Test
    public void testSignPostAsGet() throws Exception
    {
        String nonce = "test-nonce-aaaaa";
        String url = "https://acme.example.com/resource";

        // null payload means POST-as-GET
        String jwsJson = signer.sign(null, nonce, url);

        @SuppressWarnings("unchecked")
        Map<String, String> jws = (Map<String, String>)json.fromJSON(jwsJson);

        // Payload should be empty string for POST-as-GET
        assertThat(jws.get("payload"), equalTo(""));
    }

    @Test
    public void testSetAndGetAccountUrl()
    {
        assertThat(signer.getAccountUrl(), is(nullValue()));

        String accountUrl = "https://acme.example.com/acct/99999";
        signer.setAccountUrl(accountUrl);

        assertThat(signer.getAccountUrl(), equalTo(accountUrl));
    }

    @Test
    public void testSignatureIsValid()
    {
        // Just verify that signing doesn't throw
        Map<String, Object> payload = Map.of("resource", "new-reg");
        String nonce = "valid-nonce";
        String url = "https://acme.example.com/new-account";

        assertDoesNotThrow(() -> signer.sign(payload, nonce, url));
    }
}
