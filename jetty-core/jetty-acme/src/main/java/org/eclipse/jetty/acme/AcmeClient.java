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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.StringRequestContent;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.util.ajax.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>ACME protocol client implementation using Jetty HttpClient.</p>
 * <p>Implements RFC 8555 (ACME) protocol operations including:</p>
 * <ul>
 *   <li>Directory discovery</li>
 *   <li>Account registration</li>
 *   <li>Order creation and finalization</li>
 *   <li>HTTP-01 challenge handling</li>
 *   <li>Certificate retrieval</li>
 * </ul>
 */
public class AcmeClient
{
    private static final Logger LOG = LoggerFactory.getLogger(AcmeClient.class);
    private static final String JOSE_JSON_CONTENT_TYPE = "application/jose+json";
    private static final String PEM_CHAIN_CONTENT_TYPE = "application/pem-certificate-chain";
    private static final long REQUEST_TIMEOUT_MS = 30000;
    private static final int MAX_POLL_ATTEMPTS = 30;
    private static final long POLL_DELAY_MS = 2000;
    private static final int MAX_NONCE_RETRY_ATTEMPTS = 5;

    private final HttpClient _httpClient;
    private final JSON _json;
    private final AcmeJwsSigner _signer;
    private final String _directoryUrl;

    private Map<String, Object> _directory;
    private String _lastNonce;

    /**
     * Creates a new ACME client.
     *
     * @param httpClient the HTTP client to use for requests
     * @param accountKeyPair the account key pair for signing
     * @param directoryUrl the ACME directory URL
     */
    public AcmeClient(HttpClient httpClient, KeyPair accountKeyPair, String directoryUrl)
    {
        _httpClient = Objects.requireNonNull(httpClient, "httpClient");
        _directoryUrl = Objects.requireNonNull(directoryUrl, "directoryUrl");
        _json = new JSON();
        _signer = new AcmeJwsSigner(accountKeyPair, _json);
    }

    /**
     * Fetches the ACME directory to discover endpoint URLs.
     *
     * @throws AcmeException if the directory cannot be fetched
     */
    public void fetchDirectory() throws AcmeException
    {
        try
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Fetching ACME directory from {}", _directoryUrl);

            ContentResponse response = _httpClient.GET(_directoryUrl);

            if (response.getStatus() != HttpStatus.OK_200)
            {
                throw new AcmeException("Failed to fetch directory: HTTP " + response.getStatus());
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>)_json.fromJSON(response.getContentAsString());
            _directory = result;

            if (LOG.isDebugEnabled())
                LOG.debug("Directory endpoints: {}", _directory.keySet());
        }
        catch (AcmeException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new AcmeException("Failed to fetch directory", e);
        }
    }

    /**
     * Fetches a fresh nonce from the ACME server.
     *
     * @return the nonce value
     * @throws AcmeException if the nonce cannot be fetched
     */
    public String fetchNonce() throws AcmeException
    {
        try
        {
            String newNonceUrl = getEndpoint("newNonce");
            ContentResponse response = _httpClient.newRequest(newNonceUrl)
                .method(HttpMethod.HEAD)
                .timeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .send();

            String nonce = response.getHeaders().get("Replay-Nonce");
            if (nonce == null)
                throw new AcmeException("No Replay-Nonce header in response");

            _lastNonce = nonce;
            return nonce;
        }
        catch (AcmeException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new AcmeException("Failed to fetch nonce", e);
        }
    }

    /**
     * Creates a new account or finds an existing one.
     *
     * @param email the contact email address
     * @param termsOfServiceAgreed whether the terms of service are agreed to
     * @return the account URL
     * @throws AcmeException if account creation fails
     */
    public String createAccount(String email, boolean termsOfServiceAgreed) throws AcmeException
    {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("termsOfServiceAgreed", termsOfServiceAgreed);

        if (email != null && !email.isEmpty())
        {
            payload.put("contact", new Object[]{"mailto:" + email});
        }

        Map<String, Object> response = signedPost(getEndpoint("newAccount"), payload, true);

        String accountUrl = (String)response.get("_location");
        if (accountUrl == null)
            throw new AcmeException("No Location header in account response");

        _signer.setAccountUrl(accountUrl);

        if (LOG.isDebugEnabled())
            LOG.debug("Account URL: {}", accountUrl);

        return accountUrl;
    }

    /**
     * Creates a new order for the specified domains.
     *
     * @param domains the domain names to include in the certificate
     * @return the order response containing authorization URLs
     * @throws AcmeException if order creation fails
     */
    public Map<String, Object> createOrder(List<String> domains) throws AcmeException
    {
        List<Map<String, String>> identifiers = new ArrayList<>();
        for (String domain : domains)
        {
            Map<String, String> identifier = new LinkedHashMap<>();
            identifier.put("type", "dns");
            identifier.put("value", domain);
            identifiers.add(identifier);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("identifiers", identifiers.toArray());

        Map<String, Object> response = signedPost(getEndpoint("newOrder"), payload, true);

        if (LOG.isDebugEnabled())
            LOG.debug("Order created: {}", response.get("_location"));

        return response;
    }

    /**
     * Fetches an authorization and its challenges.
     *
     * @param authorizationUrl the authorization URL
     * @return the authorization response containing challenges
     * @throws AcmeException if fetching fails
     */
    public Map<String, Object> getAuthorization(String authorizationUrl) throws AcmeException
    {
        // POST-as-GET (empty payload)
        return signedPost(authorizationUrl, null, false);
    }

    /**
     * Responds to a challenge to indicate readiness.
     *
     * @param challengeUrl the challenge URL
     * @return the challenge response
     * @throws AcmeException if the challenge response fails
     */
    public Map<String, Object> respondToChallenge(String challengeUrl) throws AcmeException
    {
        // Empty object payload to trigger validation
        Map<String, Object> payload = new LinkedHashMap<>();
        return signedPost(challengeUrl, payload, false);
    }

    /**
     * Polls for challenge completion.
     *
     * @param challengeUrl the challenge URL to poll
     * @return the final challenge status
     * @throws AcmeException if polling fails or times out
     */
    public Map<String, Object> pollChallengeStatus(String challengeUrl) throws AcmeException
    {
        for (int i = 0; i < MAX_POLL_ATTEMPTS; i++)
        {
            Map<String, Object> challenge = signedPost(challengeUrl, null, false);
            String status = (String)challenge.get("status");

            if (LOG.isDebugEnabled())
                LOG.debug("Challenge status: {}", status);

            if ("valid".equals(status))
                return challenge;

            if ("invalid".equals(status))
            {
                @SuppressWarnings("unchecked")
                Map<String, Object> error = (Map<String, Object>)challenge.get("error");
                String errorDetail = error != null ? (String)error.get("detail") : "Unknown error";
                throw new AcmeException("Challenge failed: " + errorDetail);
            }

            try
            {
                Thread.sleep(POLL_DELAY_MS);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                throw new AcmeException("Polling interrupted", e);
            }
        }

        throw new AcmeException("Challenge polling timed out");
    }

    /**
     * Polls for order completion.
     *
     * @param orderUrl the order URL to poll
     * @return the final order status
     * @throws AcmeException if polling fails or times out
     */
    public Map<String, Object> pollOrderStatus(String orderUrl) throws AcmeException
    {
        for (int i = 0; i < MAX_POLL_ATTEMPTS; i++)
        {
            Map<String, Object> order = signedPost(orderUrl, null, false);
            String status = (String)order.get("status");

            if (LOG.isDebugEnabled())
                LOG.debug("Order status: {}", status);

            if ("valid".equals(status))
                return order;

            if ("invalid".equals(status))
            {
                throw new AcmeException("Order failed");
            }

            if ("ready".equals(status))
                return order;

            try
            {
                Thread.sleep(POLL_DELAY_MS);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                throw new AcmeException("Polling interrupted", e);
            }
        }

        throw new AcmeException("Order polling timed out");
    }

    /**
     * Finalizes an order with a CSR.
     *
     * @param finalizeUrl the finalize URL from the order
     * @param csrDer the DER-encoded CSR
     * @return the finalize response
     * @throws AcmeException if finalization fails
     */
    public Map<String, Object> finalizeOrder(String finalizeUrl, byte[] csrDer) throws AcmeException
    {
        String csrB64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(csrDer);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("csr", csrB64);

        return signedPost(finalizeUrl, payload, false);
    }

    /**
     * Downloads the certificate chain.
     *
     * @param certificateUrl the certificate URL from the order
     * @return the list of certificates in the chain
     * @throws AcmeException if download fails
     */
    public List<X509Certificate> downloadCertificate(String certificateUrl) throws AcmeException
    {
        try
        {
            ensureNonce();

            String jwsBody = _signer.sign(null, _lastNonce, certificateUrl);

            Request request = _httpClient.newRequest(certificateUrl)
                .method(HttpMethod.POST)
                .headers(headers -> headers.put(HttpHeader.CONTENT_TYPE, JOSE_JSON_CONTENT_TYPE))
                .headers(headers -> headers.put(HttpHeader.ACCEPT, PEM_CHAIN_CONTENT_TYPE))
                .body(new StringRequestContent(JOSE_JSON_CONTENT_TYPE, jwsBody))
                .timeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            ContentResponse response = request.send();
            _lastNonce = response.getHeaders().get("Replay-Nonce");

            if (response.getStatus() != HttpStatus.OK_200)
            {
                throw new AcmeException("Failed to download certificate: HTTP " + response.getStatus());
            }

            String pemChain = response.getContentAsString();
            return parsePemChain(pemChain);
        }
        catch (AcmeException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new AcmeException("Failed to download certificate", e);
        }
    }

    /**
     * Computes the key authorization for an HTTP-01 challenge.
     *
     * @param token the challenge token
     * @return the key authorization string
     * @throws AcmeException if computation fails
     */
    public String computeKeyAuthorization(String token) throws AcmeException
    {
        return _signer.computeKeyAuthorization(token);
    }

    private Map<String, Object> signedPost(String url, Object payload, boolean expectLocation) throws AcmeException
    {
        for (int attempt = 0; attempt < MAX_NONCE_RETRY_ATTEMPTS; attempt++)
        {
            try
            {
                return doSignedPost(url, payload);
            }
            catch (AcmeException e)
            {
                // Retry on badNonce errors (nonce expired or invalid)
                if (e.isBadNonce() && attempt < MAX_NONCE_RETRY_ATTEMPTS - 1)
                {
                    LOG.debug("BadNonce error, retrying (attempt {}/{})", attempt + 1, MAX_NONCE_RETRY_ATTEMPTS);
                    _lastNonce = null; // Force fresh nonce on retry
                    continue;
                }
                // Log rate limit warnings
                if (e.isRateLimited())
                {
                    LOG.warn("ACME rate limit exceeded: {}", e.getMessage());
                }
                throw e;
            }
        }
        throw new AcmeException("Max nonce retry attempts exceeded");
    }

    private Map<String, Object> doSignedPost(String url, Object payload) throws AcmeException
    {
        try
        {
            ensureNonce();

            String jwsBody = _signer.sign(payload, _lastNonce, url);

            Request request = _httpClient.newRequest(url)
                .method(HttpMethod.POST)
                .headers(headers -> headers.put(HttpHeader.CONTENT_TYPE, JOSE_JSON_CONTENT_TYPE))
                .body(new StringRequestContent(JOSE_JSON_CONTENT_TYPE, jwsBody))
                .timeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            ContentResponse response = request.send();

            // Always capture the new nonce
            String nonce = response.getHeaders().get("Replay-Nonce");
            if (nonce != null)
                _lastNonce = nonce;

            int status = response.getStatus();
            String content = response.getContentAsString();

            if (LOG.isDebugEnabled())
                LOG.debug("POST {} -> {} {}", url, status, content.length() > 200 ? content.substring(0, 200) + "..." : content);

            // Check for error response
            if (status >= 400)
            {
                @SuppressWarnings("unchecked")
                Map<String, Object> error = (Map<String, Object>)_json.fromJSON(content);
                String type = (String)error.get("type");
                String detail = (String)error.get("detail");
                String retryAfter = response.getHeaders().get("Retry-After");
                throw new AcmeException(detail != null ? detail : "ACME error", type, status, retryAfter);
            }

            // Parse response
            @SuppressWarnings("unchecked")
            Map<String, Object> result = content.isEmpty() ? new LinkedHashMap<>()
                : (Map<String, Object>)_json.fromJSON(content);

            // Store location if present
            String location = response.getHeaders().get("Location");
            if (location != null)
                result.put("_location", location);

            return result;
        }
        catch (AcmeException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new AcmeException("Failed to POST to " + url, e);
        }
    }

    private void ensureNonce() throws AcmeException
    {
        if (_lastNonce == null)
            fetchNonce();
    }

    private String getEndpoint(String name) throws AcmeException
    {
        if (_directory == null)
            throw new AcmeException("Directory not fetched");

        String endpoint = (String)_directory.get(name);
        if (endpoint == null)
            throw new AcmeException("No endpoint for: " + name);

        return endpoint;
    }

    private List<X509Certificate> parsePemChain(String pemChain) throws Exception
    {
        List<X509Certificate> certificates = new ArrayList<>();
        CertificateFactory cf = CertificateFactory.getInstance("X.509");

        // Split PEM chain by certificate boundaries
        String[] pems = pemChain.split("(?=-----BEGIN CERTIFICATE-----)");
        for (String pem : pems)
        {
            pem = pem.trim();
            if (pem.isEmpty())
                continue;

            ByteArrayInputStream bis = new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8));
            X509Certificate cert = (X509Certificate)cf.generateCertificate(bis);
            certificates.add(cert);
        }

        return certificates;
    }
}
