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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Handler for ACME HTTP-01 challenges.</p>
 * <p>This handler serves challenge responses at the well-known path
 * {@code /.well-known/acme-challenge/{token}}. It should be installed
 * early in the handler chain to intercept challenge requests before
 * other handlers.</p>
 *
 * <p>The handler stores pending challenges in memory and serves
 * the key authorization when the ACME server validates the domain.</p>
 *
 * <h2>Usage</h2>
 * <pre>
 * AcmeChallengeHandler challengeHandler = new AcmeChallengeHandler();
 * server.insertHandler(challengeHandler);
 *
 * // Later, when setting up a challenge:
 * challengeHandler.addChallenge(token, keyAuthorization);
 * </pre>
 */
@ManagedObject("ACME Challenge Handler")
public class AcmeChallengeHandler extends Handler.Abstract.NonBlocking
{
    private static final Logger LOG = LoggerFactory.getLogger(AcmeChallengeHandler.class);

    /**
     * The well-known path prefix for ACME challenges.
     */
    public static final String CHALLENGE_PATH_PREFIX = "/.well-known/acme-challenge/";

    private final ConcurrentMap<String, String> challenges = new ConcurrentHashMap<>();

    /**
     * Creates a new ACME challenge handler.
     */
    public AcmeChallengeHandler()
    {
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback)
    {
        String path = request.getHttpURI().getPath();

        // Only handle requests to the challenge path
        if (!path.startsWith(CHALLENGE_PATH_PREFIX))
            return false;

        String token = path.substring(CHALLENGE_PATH_PREFIX.length());

        // Ignore empty tokens
        if (token.isEmpty())
            return false;

        String keyAuthorization = challenges.get(token);

        if (keyAuthorization == null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Challenge not found for token: {}", token);
            return false;
        }

        if (LOG.isDebugEnabled())
            LOG.debug("Serving challenge response for token: {}", token);

        // Serve the key authorization
        response.setStatus(HttpStatus.OK_200);
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain; charset=utf-8");
        response.getHeaders().put(HttpHeader.CACHE_CONTROL, "no-store");

        byte[] content = keyAuthorization.getBytes(StandardCharsets.US_ASCII);
        response.getHeaders().put(HttpHeader.CONTENT_LENGTH, content.length);

        response.write(true, ByteBuffer.wrap(content), callback);
        return true;
    }

    /**
     * Adds a challenge for the ACME server to validate.
     *
     * @param token the challenge token
     * @param keyAuthorization the key authorization to serve
     */
    @ManagedOperation(value = "Add a challenge", impact = "ACTION")
    public void addChallenge(String token, String keyAuthorization)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Adding challenge: token={}", token);

        challenges.put(token, keyAuthorization);
    }

    /**
     * Removes a challenge after it has been completed or is no longer needed.
     *
     * @param token the challenge token to remove
     */
    @ManagedOperation(value = "Remove a challenge", impact = "ACTION")
    public void removeChallenge(String token)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Removing challenge: token={}", token);

        challenges.remove(token);
    }

    /**
     * @return the number of pending challenges
     */
    @ManagedAttribute("Number of pending challenges")
    public int getPendingChallengeCount()
    {
        return challenges.size();
    }

    /**
     * Clears all pending challenges.
     */
    @ManagedOperation(value = "Clear all challenges", impact = "ACTION")
    public void clearChallenges()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Clearing all challenges");

        challenges.clear();
    }
}
