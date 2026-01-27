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

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayContaining;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

public class AcmeKeyStoreManagerTest
{
    @TempDir
    Path tempDir;

    private AcmeKeyStoreManager keyStoreManager;

    @BeforeEach
    public void setUp()
    {
        keyStoreManager = new AcmeKeyStoreManager(tempDir);
    }

    @Test
    public void testGenerateDomainKeyPair() throws Exception
    {
        KeyPair keyPair = keyStoreManager.generateDomainKeyPair();

        assertThat(keyPair, is(notNullValue()));
        assertThat(keyPair.getPrivate(), is(notNullValue()));
        assertThat(keyPair.getPublic(), is(notNullValue()));
        assertThat(keyPair.getPrivate().getAlgorithm(), equalTo("RSA"));
    }

    @Test
    public void testLoadOrGenerateAccountKey() throws Exception
    {
        Path accountKeyPath = Path.of("acme/account.key");

        // First call should generate
        KeyPair keyPair1 = keyStoreManager.loadOrGenerateAccountKey(accountKeyPath);
        assertThat(keyPair1, is(notNullValue()));

        // Key file should exist
        Path fullPath = tempDir.resolve(accountKeyPath);
        assertThat(Files.exists(fullPath), is(true));

        // Second call should load the same key
        KeyPair keyPair2 = keyStoreManager.loadOrGenerateAccountKey(accountKeyPath);
        assertThat(keyPair2, is(notNullValue()));

        // Should be the same key
        assertThat(keyPair1.getPublic().getEncoded(), equalTo(keyPair2.getPublic().getEncoded()));
    }

    @Test
    public void testGenerateCSR() throws Exception
    {
        KeyPair keyPair = keyStoreManager.generateDomainKeyPair();
        List<String> domains = List.of("example.com", "www.example.com");

        byte[] csr = keyStoreManager.generateCSR(keyPair, domains);

        assertThat(csr, is(notNullValue()));
        assertThat(csr.length, greaterThan(100));
        // DER encoded CSR starts with SEQUENCE tag (0x30)
        assertThat(csr[0], equalTo((byte)0x30));
    }

    @Test
    public void testGenerateSelfSignedCertificate() throws Exception
    {
        KeyPair keyPair = keyStoreManager.generateDomainKeyPair();
        List<String> domains = List.of("example.com", "www.example.com");

        X509Certificate cert = keyStoreManager.generateSelfSignedCertificate(keyPair, domains, 90);

        assertThat(cert, is(notNullValue()));
        assertThat(cert.getSubjectX500Principal().getName(), containsString("CN=example.com"));
        assertThat(cert.getIssuerX500Principal().getName(), containsString("CN=example.com"));

        // Check SAN
        assertThat(cert.getSubjectAlternativeNames(), is(notNullValue()));
    }

    @Test
    public void testStoreCertificates() throws Exception
    {
        KeyPair keyPair = keyStoreManager.generateDomainKeyPair();
        List<String> domains = List.of("test.example.com");
        X509Certificate cert = keyStoreManager.generateSelfSignedCertificate(keyPair, domains, 30);

        Path keystorePath = Path.of("etc/keystore.p12");
        String password = "testpassword";

        keyStoreManager.storeCertificates(keystorePath, password, keyPair.getPrivate(),
            Collections.singletonList(cert));

        // Verify file exists
        Path fullPath = tempDir.resolve(keystorePath);
        assertThat(Files.exists(fullPath), is(true));
    }

    @Test
    public void testLoadCertificate() throws Exception
    {
        KeyPair keyPair = keyStoreManager.generateDomainKeyPair();
        List<String> domains = List.of("load.example.com");
        X509Certificate cert = keyStoreManager.generateSelfSignedCertificate(keyPair, domains, 30);

        Path keystorePath = Path.of("etc/test-load.p12");
        String password = "loadpassword";

        keyStoreManager.storeCertificates(keystorePath, password, keyPair.getPrivate(),
            Collections.singletonList(cert));

        // Load the certificate back
        X509Certificate loadedCert = keyStoreManager.loadCertificate(keystorePath, password);

        assertThat(loadedCert, is(notNullValue()));
        assertThat(loadedCert.getSubjectX500Principal().getName(),
            equalTo(cert.getSubjectX500Principal().getName()));
    }

    @Test
    public void testLoadCertificateFromNonExistentFile() throws Exception
    {
        Path keystorePath = Path.of("nonexistent/keystore.p12");

        X509Certificate cert = keyStoreManager.loadCertificate(keystorePath, "password");

        assertThat(cert, is(nullValue()));
    }
}
