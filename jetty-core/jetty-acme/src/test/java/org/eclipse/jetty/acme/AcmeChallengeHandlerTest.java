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

import java.net.URI;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

public class AcmeChallengeHandlerTest
{
    private Server server;
    private LocalConnector connector;
    private AcmeChallengeHandler challengeHandler;

    @BeforeEach
    public void setUp() throws Exception
    {
        server = new Server();
        connector = new LocalConnector(server);
        server.addConnector(connector);

        challengeHandler = new AcmeChallengeHandler();

        // Add a fallback handler
        Handler.Sequence sequence = new Handler.Sequence();
        sequence.addHandler(challengeHandler);
        sequence.addHandler(new Handler.Abstract.NonBlocking()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                response.setStatus(HttpStatus.NOT_FOUND_404);
                callback.succeeded();
                return true;
            }
        });

        server.setHandler(sequence);
        server.start();
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        server.stop();
    }

    @Test
    public void testNoChallengeReturnsNotFound() throws Exception
    {
        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setURI("/.well-known/acme-challenge/test-token");
        request.setVersion("HTTP/1.1");
        request.setHeader("Host", "localhost");

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request.generate()));

        assertThat(response.getStatus(), is(HttpStatus.NOT_FOUND_404));
    }

    @Test
    public void testChallengeReturnsAuthorization() throws Exception
    {
        String token = "test-token-12345";
        String keyAuth = "test-token-12345.thumbprint-abc123";

        challengeHandler.addChallenge(token, keyAuth);

        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setURI("/.well-known/acme-challenge/" + token);
        request.setVersion("HTTP/1.1");
        request.setHeader("Host", "localhost");

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request.generate()));

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.get(HttpHeader.CONTENT_TYPE), containsString("text/plain"));
        assertThat(response.getContent(), equalTo(keyAuth));
    }

    @Test
    public void testChallengeRemoval() throws Exception
    {
        String token = "test-token-67890";
        String keyAuth = "test-token-67890.thumbprint-xyz789";

        challengeHandler.addChallenge(token, keyAuth);
        challengeHandler.removeChallenge(token);

        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setURI("/.well-known/acme-challenge/" + token);
        request.setVersion("HTTP/1.1");
        request.setHeader("Host", "localhost");

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request.generate()));

        assertThat(response.getStatus(), is(HttpStatus.NOT_FOUND_404));
    }

    @Test
    public void testNonChallengePathNotHandled() throws Exception
    {
        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setURI("/some/other/path");
        request.setVersion("HTTP/1.1");
        request.setHeader("Host", "localhost");

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request.generate()));

        // Falls through to the 404 handler
        assertThat(response.getStatus(), is(HttpStatus.NOT_FOUND_404));
    }

    @Test
    public void testPendingChallengeCount()
    {
        assertThat(challengeHandler.getPendingChallengeCount(), is(0));

        challengeHandler.addChallenge("token1", "auth1");
        assertThat(challengeHandler.getPendingChallengeCount(), is(1));

        challengeHandler.addChallenge("token2", "auth2");
        assertThat(challengeHandler.getPendingChallengeCount(), is(2));

        challengeHandler.removeChallenge("token1");
        assertThat(challengeHandler.getPendingChallengeCount(), is(1));

        challengeHandler.clearChallenges();
        assertThat(challengeHandler.getPendingChallengeCount(), is(0));
    }
}
