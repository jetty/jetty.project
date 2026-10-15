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

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

public class AcmeCertificateManagerTest
{
    @TempDir
    Path tempDir;

    private Server server;
    private AcmeConfiguration config;
    private AcmeChallengeHandler challengeHandler;
    private SslContextFactory.Server sslContextFactory;
    private AcmeCertificateManager manager;

    @BeforeEach
    public void setUp() throws Exception
    {
        server = new Server();

        config = new AcmeConfiguration();
        config.setDryRun(true);
        config.setDomains("localhost");
        config.setAccountKeyPath(tempDir.resolve("acme/account.key").toString());
        config.setKeystorePath(tempDir.resolve("etc/keystore.p12").toString());

        challengeHandler = new AcmeChallengeHandler();

        sslContextFactory = new SslContextFactory.Server();

        manager = new AcmeCertificateManager(config, sslContextFactory, challengeHandler);
        manager.setServer(server);
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        if (manager.isStarted())
            manager.stop();
        if (server.isStarted())
            server.stop();
    }

    @Test
    public void testDryRunGeneratesSelfSignedCertificate() throws Exception
    {
        manager.start();

        // Keystore should be created
        Path keystorePath = tempDir.resolve("etc/keystore.p12");
        assertThat(Files.exists(keystorePath), is(true));

        // Account key should be created
        Path accountKeyPath = tempDir.resolve("acme/account.key");
        assertThat(Files.exists(accountKeyPath), is(true));

        assertThat(manager.isCertificateValid(), is(true));
        assertThat(manager.getDaysUntilExpiry(), greaterThan(0L));
    }

    @Test
    public void testStartAndStop() throws Exception
    {
        assertDoesNotThrow(() -> manager.start());
        assertThat(manager.isStarted(), is(true));

        assertDoesNotThrow(() -> manager.stop());
        assertThat(manager.isStopped(), is(true));
    }

    @Test
    public void testForceRenewalCheck() throws Exception
    {
        manager.start();

        // Should not throw in dry-run mode
        assertDoesNotThrow(() -> manager.forceRenewalCheck());

        assertThat(manager.isCertificateValid(), is(true));
    }

    @Test
    public void testGetConfiguration()
    {
        assertThat(manager.getConfiguration(), is(config));
    }

    @Test
    public void testDryRunWithMultipleDomains() throws Exception
    {
        config.setDomains("example.com,www.example.com,api.example.com");

        manager.start();

        assertThat(manager.isCertificateValid(), is(true));
    }
}
