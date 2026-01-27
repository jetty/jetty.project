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

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class AcmeConfigurationTest
{
    @Test
    public void testDefaultConfiguration()
    {
        AcmeConfiguration config = new AcmeConfiguration();

        assertThat(config.isDryRun(), is(true));
        assertThat(config.getDirectoryUrl(), equalTo(AcmeConfiguration.LETSENCRYPT_STAGING_URL));
        assertThat(config.getDomains(), is(empty()));
        assertThat(config.getRenewalThresholdDays(), is(30));
        assertThat(config.getCheckInterval(), equalTo(Duration.ofDays(1)));
        assertThat(config.isTermsOfServiceAgreed(), is(false));
    }

    @Test
    public void testSetDomainsFromString()
    {
        AcmeConfiguration config = new AcmeConfiguration();

        config.setDomains("example.com,www.example.com");

        assertThat(config.getDomains(), contains("example.com", "www.example.com"));
    }

    @Test
    public void testSetDomainsWithSpaces()
    {
        AcmeConfiguration config = new AcmeConfiguration();

        config.setDomains("example.com , www.example.com , api.example.com");

        assertThat(config.getDomains(), contains("example.com", "www.example.com", "api.example.com"));
    }

    @Test
    public void testSetDomainsFromList()
    {
        AcmeConfiguration config = new AcmeConfiguration();

        config.setDomains(List.of("example.com", "www.example.com"));

        assertThat(config.getDomains(), contains("example.com", "www.example.com"));
    }

    @Test
    public void testSetAccountKeyPath()
    {
        AcmeConfiguration config = new AcmeConfiguration();

        config.setAccountKeyPath("custom/path/account.key");

        assertThat(config.getAccountKeyPath(), equalTo(Path.of("custom/path/account.key")));
    }

    @Test
    public void testSetKeystorePath()
    {
        AcmeConfiguration config = new AcmeConfiguration();

        config.setKeystorePath("custom/keystore.p12");

        assertThat(config.getKeystorePath(), equalTo(Path.of("custom/keystore.p12")));
    }

    @Test
    public void testValidateInDryRunMode()
    {
        AcmeConfiguration config = new AcmeConfiguration();
        config.setDryRun(true);

        // Validation should pass even without required fields
        assertDoesNotThrow(config::validate);
    }

    @Test
    public void testValidateRequiresDomains()
    {
        AcmeConfiguration config = new AcmeConfiguration();
        config.setDryRun(false);
        config.setAccountEmail("admin@example.com");
        config.setTermsOfServiceAgreed(true);

        IllegalStateException e = assertThrows(IllegalStateException.class, config::validate);
        assertThat(e.getMessage(), equalTo("At least one domain must be configured"));
    }

    @Test
    public void testValidateRequiresEmail()
    {
        AcmeConfiguration config = new AcmeConfiguration();
        config.setDryRun(false);
        config.setDomains("example.com");
        config.setTermsOfServiceAgreed(true);

        IllegalStateException e = assertThrows(IllegalStateException.class, config::validate);
        assertThat(e.getMessage(), equalTo("Account email must be configured"));
    }

    @Test
    public void testValidateRequiresTermsAgreement()
    {
        AcmeConfiguration config = new AcmeConfiguration();
        config.setDryRun(false);
        config.setDomains("example.com");
        config.setAccountEmail("admin@example.com");
        config.setTermsOfServiceAgreed(false);

        IllegalStateException e = assertThrows(IllegalStateException.class, config::validate);
        assertThat(e.getMessage(), equalTo("Terms of service must be agreed to for production use"));
    }

    @Test
    public void testValidatePassesWithAllRequired()
    {
        AcmeConfiguration config = new AcmeConfiguration();
        config.setDryRun(false);
        config.setDomains("example.com");
        config.setAccountEmail("admin@example.com");
        config.setTermsOfServiceAgreed(true);

        assertDoesNotThrow(config::validate);
    }

    @Test
    public void testToString()
    {
        AcmeConfiguration config = new AcmeConfiguration();
        config.setDomains("example.com");
        config.setAccountEmail("admin@example.com");

        String str = config.toString();

        assertThat(str.contains("dryRun=true"), is(true));
        assertThat(str.contains("example.com"), is(true));
        assertThat(str.contains("admin@example.com"), is(true));
    }
}
