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

    /**
     * Exponential backoff intervals in seconds for renewal failures.
     * Starts at 1 minute, escalates up to 6 hours.
     */
    private static final long[] BACKOFF_INTERVALS_SECONDS = {
        60,       // 1 minute
        120,      // 2 minutes
        300,      // 5 minutes
        600,      // 10 minutes
        1200,     // 20 minutes
        1800,     // 30 minutes
        3600,     // 1 hour
        7200,     // 2 hours
        10800,    // 3 hours
        21600     // 6 hours (max)
    };

    private final AutoLock _lock = new AutoLock();
    private final AcmeConfiguration _config;
    private final SslContextFactory.Server _sslContextFactory;
    private final AcmeChallengeHandler _challengeHandler;

    private Server _server;
    private HttpClient _httpClient;
    private AcmeKeyStoreManager _keyStoreManager;
    private Scheduler _scheduler;
    private Scheduler.Task _renewalTask;
    private boolean _ownHttpClient;
    private KeyPair _accountKeyPair;
    private KeyPair _domainKeyPair;
    private int _consecutiveFailures;

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
        _config = Objects.requireNonNull(config, "config");
        _sslContextFactory = Objects.requireNonNull(sslContextFactory, "sslContextFactory");
        _challengeHandler = Objects.requireNonNull(challengeHandler, "challengeHandler");
    }

    /**
     * Sets the Server for accessing shared resources.
     *
     * @param server the server
     */
    public void setServer(Server server)
    {
        _server = server;
    }

    /**
     * @return the configuration
     */
    @ManagedAttribute("ACME Configuration")
    public AcmeConfiguration getConfiguration()
    {
        return _config;
    }

    @Override
    protected void doStart() throws Exception
    {
        if (!_config.isDryRun())
            _config.validate();

        // Get base path from server
        Path basePath = Path.of(".");
        if (_server != null)
        {
            Object jettyBase = _server.getAttribute("jetty.base");
            if (jettyBase != null)
                basePath = Path.of(jettyBase.toString());
        }

        _keyStoreManager = new AcmeKeyStoreManager(basePath);

        // Load or generate account key
        _accountKeyPair = _keyStoreManager.loadOrGenerateAccountKey(_config.getAccountKeyPath());

        if (_config.isDryRun())
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
        try (AutoLock l = _lock.lock())
        {
            if (_renewalTask != null)
            {
                _renewalTask.cancel();
                _renewalTask = null;
            }

            if (_ownHttpClient && _httpClient != null)
            {
                _httpClient.stop();
                _httpClient = null;
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
        if (_config.isDryRun())
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
            X509Certificate cert = _keyStoreManager.loadCertificate(
                _config.getKeystorePath(), _config.getKeystorePassword());
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
            X509Certificate cert = _keyStoreManager.loadCertificate(
                _config.getKeystorePath(), _config.getKeystorePassword());
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
        if (_server != null)
        {
            // Try to find an existing HttpClient bean
            _httpClient = _server.getBean(HttpClient.class);
        }

        if (_httpClient == null)
        {
            _httpClient = new HttpClient();
            _httpClient.start();
            _ownHttpClient = true;
        }
    }

    private void checkAndRenewIfNeeded() throws AcmeException
    {
        try
        {
            X509Certificate currentCert = _keyStoreManager.loadCertificate(
                _config.getKeystorePath(), _config.getKeystorePassword());

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
        Instant threshold = Instant.now().plus(_config.getRenewalThresholdDays(), ChronoUnit.DAYS);
        return expiry.isBefore(threshold);
    }

    private void obtainNewCertificate() throws AcmeException
    {
        AcmeClient acmeClient = new AcmeClient(_httpClient, _accountKeyPair, _config.getDirectoryUrl());

        try
        {
            // Step 1: Fetch directory
            LOG.info("Fetching ACME directory from {}", _config.getDirectoryUrl());
            acmeClient.fetchDirectory();

            // Step 2: Create/find account
            LOG.info("Creating/finding ACME account");
            acmeClient.createAccount(_config.getAccountEmail(), _config.isTermsOfServiceAgreed());

            // Step 3: Create order
            List<String> domains = _config.getDomains();
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
            _domainKeyPair = _keyStoreManager.generateDomainKeyPair();
            byte[] csr = _keyStoreManager.generateCSR(_domainKeyPair, domains);

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
            _keyStoreManager.storeCertificates(
                _config.getKeystorePath(),
                _config.getKeystorePassword(),
                _domainKeyPair.getPrivate(),
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
        _challengeHandler.addChallenge(token, keyAuthorization);

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
            _challengeHandler.removeChallenge(token);
        }
    }

    private void generateDryRunCertificate() throws AcmeException
    {
        List<String> domains = _config.getDomains();
        if (domains.isEmpty())
        {
            domains = Collections.singletonList("localhost");
        }

        // Generate domain key pair
        _domainKeyPair = _keyStoreManager.generateDomainKeyPair();

        // Generate self-signed certificate
        X509Certificate cert = _keyStoreManager.generateSelfSignedCertificate(
            _domainKeyPair, domains, DRY_RUN_VALIDITY_DAYS);

        // Store certificate
        _keyStoreManager.storeCertificates(
            _config.getKeystorePath(),
            _config.getKeystorePassword(),
            _domainKeyPair.getPrivate(),
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
            Path keystorePath = _config.getKeystorePath();
            if (!keystorePath.isAbsolute() && _server != null)
            {
                Object jettyBase = _server.getAttribute("jetty.base");
                if (jettyBase != null)
                    keystorePath = Path.of(jettyBase.toString()).resolve(keystorePath);
            }

            String keystorePathStr = keystorePath.toString();

            _sslContextFactory.reload(scf ->
            {
                scf.setKeyStorePath(keystorePathStr);
                scf.setKeyStorePassword(_config.getKeystorePassword());
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
        try (AutoLock l = _lock.lock())
        {
            if (_server != null)
            {
                _scheduler = _server.getScheduler();
            }

            if (_scheduler == null || !_scheduler.isStarted())
            {
                LOG.debug("No scheduler available, renewal checks will not be scheduled");
                return;
            }

            long intervalMs = _config.getCheckIntervalSeconds() * 1000;

            _renewalTask = _scheduler.schedule(new RenewalRunner(), intervalMs, TimeUnit.MILLISECONDS);

            if (LOG.isDebugEnabled())
                LOG.debug("Scheduled renewal check in {} seconds", _config.getCheckIntervalSeconds());
        }
    }

    private class RenewalRunner implements Runnable
    {
        @Override
        public void run()
        {
            boolean success = false;
            try
            {
                if (isStopping() || isStopped())
                    return;

                if (LOG.isDebugEnabled())
                    LOG.debug("Running scheduled renewal check");

                if (_config.isDryRun())
                {
                    X509Certificate cert = _keyStoreManager.loadCertificate(
                        _config.getKeystorePath(), _config.getKeystorePassword());
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
                success = true;
            }
            catch (AcmeException e)
            {
                if (e.isRateLimited())
                {
                    LOG.warn("ACME rate limit exceeded, will retry with backoff: {}", e.getMessage());
                }
                else
                {
                    LOG.warn("Certificate renewal check failed", e);
                }
            }
            catch (Exception e)
            {
                LOG.warn("Certificate renewal check failed", e);
            }
            finally
            {
                // Reschedule with backoff on failure
                try (AutoLock l = _lock.lock())
                {
                    if (_scheduler != null && _scheduler.isRunning() && isRunning())
                    {
                        long intervalMs;
                        if (success)
                        {
                            _consecutiveFailures = 0;
                            intervalMs = _config.getCheckIntervalSeconds() * 1000;
                        }
                        else
                        {
                            _consecutiveFailures++;
                            intervalMs = getBackoffIntervalMs(_consecutiveFailures);
                            LOG.info("Scheduling next renewal attempt in {} seconds (failure #{})",
                                intervalMs / 1000, _consecutiveFailures);
                        }
                        _renewalTask = _scheduler.schedule(this, intervalMs, TimeUnit.MILLISECONDS);
                    }
                }
            }
        }
    }

    /**
     * Calculates the backoff interval based on the number of consecutive failures.
     *
     * @param failureCount the number of consecutive failures
     * @return the backoff interval in milliseconds
     */
    private long getBackoffIntervalMs(int failureCount)
    {
        int index = Math.min(failureCount - 1, BACKOFF_INTERVALS_SECONDS.length - 1);
        if (index < 0)
            index = 0;
        return BACKOFF_INTERVALS_SECONDS[index] * 1000;
    }
}
