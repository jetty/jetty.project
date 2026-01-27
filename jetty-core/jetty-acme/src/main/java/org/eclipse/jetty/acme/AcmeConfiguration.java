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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;

/**
 * <p>Configuration holder for ACME certificate management.</p>
 * <p>This class stores all settings required for ACME protocol operations,
 * including the ACME directory URL, domain names, account information,
 * and keystore paths.</p>
 *
 * <h2>Example Configuration</h2>
 * <pre>
 * AcmeConfiguration config = new AcmeConfiguration();
 * config.setDomains("example.com,www.example.com");
 * config.setAccountEmail("admin@example.com");
 * config.setTermsOfServiceAgreed(true);
 * config.setDryRun(false);
 * </pre>
 */
@ManagedObject("ACME Configuration")
public class AcmeConfiguration
{
    /**
     * Let's Encrypt production directory URL.
     */
    public static final String LETSENCRYPT_PRODUCTION_URL = "https://acme-v02.api.letsencrypt.org/directory";

    /**
     * Let's Encrypt staging directory URL (for testing).
     */
    public static final String LETSENCRYPT_STAGING_URL = "https://acme-staging-v02.api.letsencrypt.org/directory";

    private boolean dryRun = true;
    private String directoryUrl = LETSENCRYPT_STAGING_URL;
    private List<String> domains = new ArrayList<>();
    private String accountEmail;
    private Path accountKeyPath = Path.of("acme/account.key");
    private Path keystorePath = Path.of("etc/keystore.p12");
    private String keystorePassword = "changeit";
    private int renewalThresholdDays = 30;
    private long checkIntervalSeconds = 86400;
    private boolean termsOfServiceAgreed = false;

    /**
     * Creates a new AcmeConfiguration with default settings.
     */
    public AcmeConfiguration()
    {
    }

    /**
     * @return true if dry-run mode is enabled (no real ACME calls)
     */
    @ManagedAttribute("Dry run mode - simulates certificate flow without contacting ACME")
    public boolean isDryRun()
    {
        return dryRun;
    }

    /**
     * Sets dry-run mode. When enabled, the manager generates self-signed
     * certificates instead of contacting the ACME server.
     *
     * @param dryRun true to enable dry-run mode
     */
    public void setDryRun(boolean dryRun)
    {
        this.dryRun = dryRun;
    }

    /**
     * @return the ACME directory URL
     */
    @ManagedAttribute("ACME directory URL")
    public String getDirectoryUrl()
    {
        return directoryUrl;
    }

    /**
     * Sets the ACME directory URL. Use {@link #LETSENCRYPT_STAGING_URL}
     * for testing or {@link #LETSENCRYPT_PRODUCTION_URL} for production.
     *
     * @param directoryUrl the ACME directory URL
     */
    public void setDirectoryUrl(String directoryUrl)
    {
        this.directoryUrl = Objects.requireNonNull(directoryUrl, "directoryUrl");
    }

    /**
     * @return the list of domain names for the certificate
     */
    @ManagedAttribute("Domain names for the certificate")
    public List<String> getDomains()
    {
        return Collections.unmodifiableList(domains);
    }

    /**
     * Sets the domain names for the certificate.
     *
     * @param domains the list of domain names
     */
    public void setDomains(List<String> domains)
    {
        this.domains = new ArrayList<>(Objects.requireNonNull(domains, "domains"));
    }

    /**
     * Sets the domain names from a comma-separated string.
     *
     * @param domains comma-separated domain names
     */
    public void setDomains(String domains)
    {
        this.domains.clear();
        if (StringUtil.isNotBlank(domains))
        {
            for (String domain : StringUtil.csvSplit(domains))
            {
                String trimmed = domain.trim();
                if (!trimmed.isEmpty())
                    this.domains.add(trimmed);
            }
        }
    }

    /**
     * @return the account email address
     */
    @ManagedAttribute("Contact email for ACME account")
    public String getAccountEmail()
    {
        return accountEmail;
    }

    /**
     * Sets the account email address for ACME registration.
     *
     * @param accountEmail the email address
     */
    public void setAccountEmail(String accountEmail)
    {
        this.accountEmail = accountEmail;
    }

    /**
     * @return the path to the account key file
     */
    @ManagedAttribute("Path to store account key")
    public Path getAccountKeyPath()
    {
        return accountKeyPath;
    }

    /**
     * Sets the path where the ACME account private key will be stored.
     *
     * @param accountKeyPath the path to the account key file
     */
    public void setAccountKeyPath(Path accountKeyPath)
    {
        this.accountKeyPath = Objects.requireNonNull(accountKeyPath, "accountKeyPath");
    }

    /**
     * Sets the account key path from a string.
     *
     * @param accountKeyPath the path string
     */
    public void setAccountKeyPath(String accountKeyPath)
    {
        if (StringUtil.isNotBlank(accountKeyPath))
            this.accountKeyPath = Path.of(accountKeyPath);
    }

    /**
     * @return the keystore path
     */
    @ManagedAttribute("Keystore path")
    public Path getKeystorePath()
    {
        return keystorePath;
    }

    /**
     * Sets the path to the keystore where certificates will be stored.
     *
     * @param keystorePath the keystore path
     */
    public void setKeystorePath(Path keystorePath)
    {
        this.keystorePath = Objects.requireNonNull(keystorePath, "keystorePath");
    }

    /**
     * Sets the keystore path from a string.
     *
     * @param keystorePath the path string
     */
    public void setKeystorePath(String keystorePath)
    {
        if (StringUtil.isNotBlank(keystorePath))
            this.keystorePath = Path.of(keystorePath);
    }

    /**
     * @return the keystore password
     */
    @ManagedAttribute("Keystore password")
    public String getKeystorePassword()
    {
        return keystorePassword;
    }

    /**
     * Sets the keystore password.
     *
     * @param keystorePassword the password
     */
    public void setKeystorePassword(String keystorePassword)
    {
        this.keystorePassword = keystorePassword;
    }

    /**
     * @return days before expiry to trigger renewal
     */
    @ManagedAttribute("Days before expiry to trigger renewal")
    public int getRenewalThresholdDays()
    {
        return renewalThresholdDays;
    }

    /**
     * Sets the number of days before certificate expiry to trigger renewal.
     *
     * @param renewalThresholdDays the renewal threshold in days
     */
    public void setRenewalThresholdDays(int renewalThresholdDays)
    {
        this.renewalThresholdDays = renewalThresholdDays;
    }

    /**
     * @return the renewal check interval in seconds
     */
    @ManagedAttribute("Renewal check interval in seconds")
    public long getCheckIntervalSeconds()
    {
        return checkIntervalSeconds;
    }

    /**
     * Sets how often to check if certificates need renewal.
     *
     * @param checkIntervalSeconds the interval in seconds
     */
    public void setCheckIntervalSeconds(long checkIntervalSeconds)
    {
        this.checkIntervalSeconds = checkIntervalSeconds;
    }

    /**
     * @return true if the ACME terms of service have been agreed to
     */
    @ManagedAttribute("Terms of service agreement")
    public boolean isTermsOfServiceAgreed()
    {
        return termsOfServiceAgreed;
    }

    /**
     * Sets whether the ACME terms of service have been agreed to.
     * This must be true for production use.
     *
     * @param termsOfServiceAgreed true if terms are agreed
     */
    public void setTermsOfServiceAgreed(boolean termsOfServiceAgreed)
    {
        this.termsOfServiceAgreed = termsOfServiceAgreed;
    }

    /**
     * Validates this configuration.
     *
     * @throws IllegalStateException if the configuration is invalid
     */
    public void validate()
    {
        if (dryRun)
            return;

        if (domains.isEmpty())
            throw new IllegalStateException("At least one domain must be configured");
        if (StringUtil.isBlank(accountEmail))
            throw new IllegalStateException("Account email must be configured");
        if (!termsOfServiceAgreed)
            throw new IllegalStateException("Terms of service must be agreed to for production use");
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{dryRun=%b,directoryUrl=%s,domains=%s,accountEmail=%s}",
            getClass().getSimpleName(),
            hashCode(),
            dryRun,
            directoryUrl,
            domains,
            accountEmail);
    }
}
