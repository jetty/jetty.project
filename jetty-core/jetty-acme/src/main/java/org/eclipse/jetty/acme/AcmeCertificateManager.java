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
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Main orchestrator for ACME certificate management.</p>
 * <p>This component manages the complete lifecycle of TLS certificates:</p>
 * <ul>
 *   <li>Initial certificate acquisition</li>
 *   <li>Periodic renewal checks</li>
 *   <li>Challenge response handling</li>
 *   <li>Hot-reload of renewed certificates</li>
 * </ul>
 *
 * <p>The manager supports dry-run mode for testing without contacting
 * the ACME server. In dry-run mode, self-signed certificates are
 * generated instead.</p>
 *
 * <h2>Usage</h2>
 * <pre>
 * AcmeConfiguration config = new AcmeConfiguration();
 * config.setDomains("example.com");
 * config.setAccountEmail("admin@example.com");
 * config.setTermsOfServiceAgreed(true);
 * config.setDryRun(false);
 *
 * AcmeChallengeHandler challengeHandler = new AcmeChallengeHandler();
 * server.insertHandler(challengeHandler);
 *
 * AcmeCertificateManager manager = new AcmeCertificateManager(
 *     config, sslContextFactory, challengeHandler);
 * server.addBean(manager);
 * </pre>
 */
@ManagedObject("ACME Certificate Manager")
public class AcmeCertificateManager extends AbstractLifeCycle
{
    private static final Logger LOG = LoggerFactory.getLogger(AcmeCertificateManager.class);
    private static final int DRY_RUN_VALIDITY_DAYS = 90;

    private final AutoLock lock = new AutoLock();
    private final AcmeConfiguration config;
    private final SslContextFactory.Server sslContextFactory;
    private final AcmeChallengeHandler challengeHandler;

    private Server server;
    private HttpClient httpClient;
    private AcmeKeyStoreManager keyStoreManager;
    private Scheduler scheduler;
    private Scheduler.Task renewalTask;
    private boolean ownHttpClient;
    private KeyPair accountKeyPair;
    private KeyPair domainKeyPair;

    /**
     * Creates a new ACME certificate manager.
     *
     * @param config the ACME configuration
     * @param sslContextFactory the SSL context factory to update with certificates
     * @param challengeHandler the challenge handler for HTTP-01 challenges
     */
    public AcmeCertificateManager(AcmeConfiguration config, SslContextFactory.Server sslContextFactory,
                                   AcmeChallengeHandler challengeHandler)
    {
        this.config = Objects.requireNonNull(config, "config");
        this.sslContextFactory = Objects.requireNonNull(sslContextFactory, "sslContextFactory");
        this.challengeHandler = Objects.requireNonNull(challengeHandler, "challengeHandler");
    }

    /**
     * Sets the Server for accessing shared resources.
     *
     * @param server the server
     */
    public void setServer(Server server)
    {
        this.server = server;
    }

    /**
     * @return the configuration
     */
    @ManagedAttribute("ACME Configuration")
    public AcmeConfiguration getConfiguration()
    {
        return config;
    }

    @Override
    protected void doStart() throws Exception
    {
        if (!config.isDryRun())
            config.validate();

        // Get base path from server
        Path basePath = Path.of(".");
        if (server != null)
        {
            Object jettyBase = server.getAttribute("jetty.base");
            if (jettyBase != null)
                basePath = Path.of(jettyBase.toString());
        }

        keyStoreManager = new AcmeKeyStoreManager(basePath);

        // Load or generate account key
        accountKeyPair = keyStoreManager.loadOrGenerateAccountKey(config.getAccountKeyPath());

        if (config.isDryRun())
        {
            LOG.info("ACME dry-run mode enabled - generating self-signed certificate");
            generateDryRunCertificate();
        }
        else
        {
            // Create HTTP client for ACME requests
            setupHttpClient();

            // Check if we need a new certificate
            checkAndRenewIfNeeded();
        }

        // Schedule periodic renewal checks
        scheduleRenewalCheck();

        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        try (AutoLock l = lock.lock())
        {
            if (renewalTask != null)
            {
                renewalTask.cancel();
                renewalTask = null;
            }

            if (ownHttpClient && httpClient != null)
            {
                httpClient.stop();
                httpClient = null;
            }
        }

        super.doStop();
    }

    /**
     * Forces an immediate certificate renewal check.
     *
     * @throws AcmeException if renewal fails
     */
    @ManagedOperation(value = "Force certificate renewal check", impact = "ACTION")
    public void forceRenewalCheck() throws AcmeException
    {
        if (config.isDryRun())
        {
            LOG.info("Dry-run mode: regenerating self-signed certificate");
            generateDryRunCertificate();
        }
        else
        {
            checkAndRenewIfNeeded();
        }
    }

    /**
     * @return true if a valid certificate is installed
     */
    @ManagedAttribute("Certificate valid")
    public boolean isCertificateValid()
    {
        try
        {
            X509Certificate cert = keyStoreManager.loadCertificate(
                config.getKeystorePath(), config.getKeystorePassword());
            return cert != null && !needsRenewal(cert);
        }
        catch (Exception e)
        {
            return false;
        }
    }

    /**
     * @return days until certificate expires, or -1 if no certificate
     */
    @ManagedAttribute("Days until certificate expires")
    public long getDaysUntilExpiry()
    {
        try
        {
            X509Certificate cert = keyStoreManager.loadCertificate(
                config.getKeystorePath(), config.getKeystorePassword());
            if (cert == null)
                return -1;

            Instant expiry = cert.getNotAfter().toInstant();
            return ChronoUnit.DAYS.between(Instant.now(), expiry);
        }
        catch (Exception e)
        {
            return -1;
        }
    }

    private void setupHttpClient() throws Exception
    {
        if (server != null)
        {
            // Try to find an existing HttpClient bean
            httpClient = server.getBean(HttpClient.class);
        }

        if (httpClient == null)
        {
            httpClient = new HttpClient();
            httpClient.start();
            ownHttpClient = true;
        }
    }

    private void checkAndRenewIfNeeded() throws AcmeException
    {
        try
        {
            X509Certificate currentCert = keyStoreManager.loadCertificate(
                config.getKeystorePath(), config.getKeystorePassword());

            if (currentCert == null || needsRenewal(currentCert))
            {
                LOG.info("Certificate renewal needed, starting ACME flow");
                obtainNewCertificate();
            }
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Certificate is valid, no renewal needed");
            }
        }
        catch (AcmeException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new AcmeException("Certificate check failed", e);
        }
    }

    private boolean needsRenewal(X509Certificate cert)
    {
        Instant expiry = cert.getNotAfter().toInstant();
        Instant threshold = Instant.now().plus(config.getRenewalThresholdDays(), ChronoUnit.DAYS);
        return expiry.isBefore(threshold);
    }

    private void obtainNewCertificate() throws AcmeException
    {
        AcmeClient acmeClient = new AcmeClient(httpClient, accountKeyPair, config.getDirectoryUrl());

        try
        {
            // Step 1: Fetch directory
            LOG.info("Fetching ACME directory from {}", config.getDirectoryUrl());
            acmeClient.fetchDirectory();

            // Step 2: Create/find account
            LOG.info("Creating/finding ACME account");
            acmeClient.createAccount(config.getAccountEmail(), config.isTermsOfServiceAgreed());

            // Step 3: Create order
            List<String> domains = config.getDomains();
            LOG.info("Creating order for domains: {}", domains);
            Map<String, Object> order = acmeClient.createOrder(domains);

            String orderUrl = (String)order.get("_location");
            String finalizeUrl = (String)order.get("finalize");
            Object[] authUrls = (Object[])order.get("authorizations");

            // Step 4: Complete authorizations
            for (Object authUrlObj : authUrls)
            {
                String authUrl = (String)authUrlObj;
                completeAuthorization(acmeClient, authUrl);
            }

            // Step 5: Generate domain key pair and CSR
            domainKeyPair = keyStoreManager.generateDomainKeyPair();
            byte[] csr = keyStoreManager.generateCSR(domainKeyPair, domains);

            // Step 6: Finalize order
            LOG.info("Finalizing order");
            acmeClient.finalizeOrder(finalizeUrl, csr);

            // Step 7: Poll for order completion
            Map<String, Object> finalOrder = acmeClient.pollOrderStatus(orderUrl);

            // Step 8: Download certificate
            String certUrl = (String)finalOrder.get("certificate");
            LOG.info("Downloading certificate from {}", certUrl);
            List<X509Certificate> certificates = acmeClient.downloadCertificate(certUrl);

            // Step 9: Store certificate
            LOG.info("Storing certificate chain ({} certificates)", certificates.size());
            keyStoreManager.storeCertificates(
                config.getKeystorePath(),
                config.getKeystorePassword(),
                domainKeyPair.getPrivate(),
                certificates
            );

            // Step 10: Reload SSL context
            reloadSslContext();

            LOG.info("Certificate successfully obtained and installed");
        }
        catch (AcmeException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new AcmeException("Failed to obtain certificate", e);
        }
    }

    private void completeAuthorization(AcmeClient acmeClient, String authUrl) throws AcmeException
    {
        Map<String, Object> auth = acmeClient.getAuthorization(authUrl);

        @SuppressWarnings("unchecked")
        Map<String, Object> identifier = (Map<String, Object>)auth.get("identifier");
        String domain = (String)identifier.get("value");

        Object[] challenges = (Object[])auth.get("challenges");

        // Find HTTP-01 challenge
        Map<String, Object> httpChallenge = null;
        for (Object challengeObj : challenges)
        {
            @SuppressWarnings("unchecked")
            Map<String, Object> challenge = (Map<String, Object>)challengeObj;
            if ("http-01".equals(challenge.get("type")))
            {
                httpChallenge = challenge;
                break;
            }
        }

        if (httpChallenge == null)
            throw new AcmeException("No HTTP-01 challenge available for domain: " + domain);

        String token = (String)httpChallenge.get("token");
        String challengeUrl = (String)httpChallenge.get("url");

        // Compute key authorization
        String keyAuthorization = acmeClient.computeKeyAuthorization(token);

        // Register challenge with handler
        LOG.info("Setting up HTTP-01 challenge for domain: {}", domain);
        challengeHandler.addChallenge(token, keyAuthorization);

        try
        {
            // Respond to challenge
            acmeClient.respondToChallenge(challengeUrl);

            // Poll for completion
            acmeClient.pollChallengeStatus(challengeUrl);

            LOG.info("Challenge completed for domain: {}", domain);
        }
        finally
        {
            // Clean up challenge
            challengeHandler.removeChallenge(token);
        }
    }

    private void generateDryRunCertificate() throws AcmeException
    {
        List<String> domains = config.getDomains();
        if (domains.isEmpty())
        {
            domains = Collections.singletonList("localhost");
        }

        // Generate domain key pair
        domainKeyPair = keyStoreManager.generateDomainKeyPair();

        // Generate self-signed certificate
        X509Certificate cert = keyStoreManager.generateSelfSignedCertificate(
            domainKeyPair, domains, DRY_RUN_VALIDITY_DAYS);

        // Store certificate
        keyStoreManager.storeCertificates(
            config.getKeystorePath(),
            config.getKeystorePassword(),
            domainKeyPair.getPrivate(),
            Collections.singletonList(cert)
        );

        // Reload SSL context
        reloadSslContext();

        LOG.info("Dry-run self-signed certificate installed for domains: {}", domains);
    }

    private void reloadSslContext()
    {
        try
        {
            Path keystorePath = config.getKeystorePath();
            if (!keystorePath.isAbsolute() && server != null)
            {
                Object jettyBase = server.getAttribute("jetty.base");
                if (jettyBase != null)
                    keystorePath = Path.of(jettyBase.toString()).resolve(keystorePath);
            }

            String keystorePathStr = keystorePath.toString();

            sslContextFactory.reload(scf ->
            {
                scf.setKeyStorePath(keystorePathStr);
                scf.setKeyStorePassword(config.getKeystorePassword());
            });

            LOG.info("SSL context reloaded with new certificate");
        }
        catch (Exception e)
        {
            LOG.warn("Failed to reload SSL context", e);
        }
    }

    private void scheduleRenewalCheck()
    {
        try (AutoLock l = lock.lock())
        {
            if (server != null)
            {
                scheduler = server.getScheduler();
            }

            if (scheduler == null || !scheduler.isStarted())
            {
                LOG.debug("No scheduler available, renewal checks will not be scheduled");
                return;
            }

            long intervalMs = config.getCheckIntervalSeconds() * 1000;

            renewalTask = scheduler.schedule(new RenewalRunner(), intervalMs, TimeUnit.MILLISECONDS);

            if (LOG.isDebugEnabled())
                LOG.debug("Scheduled renewal check in {} seconds", config.getCheckIntervalSeconds());
        }
    }

    private class RenewalRunner implements Runnable
    {
        @Override
        public void run()
        {
            try
            {
                if (isStopping() || isStopped())
                    return;

                if (LOG.isDebugEnabled())
                    LOG.debug("Running scheduled renewal check");

                if (config.isDryRun())
                {
                    X509Certificate cert = keyStoreManager.loadCertificate(
                        config.getKeystorePath(), config.getKeystorePassword());
                    if (cert == null || needsRenewal(cert))
                    {
                        LOG.info("Dry-run certificate needs renewal");
                        generateDryRunCertificate();
                    }
                }
                else
                {
                    checkAndRenewIfNeeded();
                }
            }
            catch (Exception e)
            {
                LOG.warn("Certificate renewal check failed", e);
            }
            finally
            {
                // Reschedule
                try (AutoLock l = lock.lock())
                {
                    if (scheduler != null && scheduler.isRunning() && isRunning())
                    {
                        long intervalMs = config.getCheckIntervalSeconds() * 1000;
                        renewalTask = scheduler.schedule(this, intervalMs, TimeUnit.MILLISECONDS);
                    }
                }
            }
        }
    }
}
