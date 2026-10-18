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

import org.eclipse.jetty.client.HttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Integration tests against Let's Encrypt staging server.
 * These tests require network access and are excluded from normal CI builds.
 */
@Tag("external")
public class AcmeStagingIntegrationTest
{
    private HttpClient httpClient;
    private KeyPair accountKeyPair;
    private AcmeClient acmeClient;

    @BeforeEach
    public void setUp() throws Exception
    {
        // Generate account key pair
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048, new SecureRandom());
        accountKeyPair = keyGen.generateKeyPair();

        // Create HTTP client
        httpClient = new HttpClient();
        httpClient.start();

        // Create ACME client for Let's Encrypt staging
        acmeClient = new AcmeClient(httpClient, accountKeyPair,
            AcmeConfiguration.LETSENCRYPT_STAGING_URL);
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        if (httpClient != null)
            httpClient.stop();
    }

    @Test
    public void testFetchDirectoryFromStaging() throws Exception
    {
        acmeClient.fetchDirectory();
        // If we get here without exception, the directory was fetched successfully
    }

    @Test
    public void testFetchNonceFromStaging() throws Exception
    {
        acmeClient.fetchDirectory();
        String nonce = acmeClient.fetchNonce();

        assertThat(nonce, is(notNullValue()));
        assertThat(nonce.isEmpty(), is(false));
    }

    @Test
    public void testCreateAccountOnStaging() throws Exception
    {
        acmeClient.fetchDirectory();

        // Create account with terms of service agreed
        String accountUrl = acmeClient.createAccount("test@example.com", true);

        assertThat(accountUrl, is(notNullValue()));
        assertThat(accountUrl.contains("acme-staging"), is(true));
    }
}
