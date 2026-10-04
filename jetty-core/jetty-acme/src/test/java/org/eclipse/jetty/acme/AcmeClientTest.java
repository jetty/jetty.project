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
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class AcmeClientTest
{
    private Server mockAcmeServer;
    private ServerConnector connector;
    private HttpClient httpClient;
    private KeyPair accountKeyPair;
    private String directoryUrl;

    @BeforeEach
    public void setUp() throws Exception
    {
        // Generate account key pair
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048, new SecureRandom());
        accountKeyPair = keyGen.generateKeyPair();

        // Create mock ACME server
        mockAcmeServer = new Server();
        connector = new ServerConnector(mockAcmeServer);
        connector.setPort(0);
        mockAcmeServer.addConnector(connector);

        mockAcmeServer.setHandler(new MockAcmeHandler());
        mockAcmeServer.start();

        directoryUrl = "http://localhost:" + connector.getLocalPort() + "/directory";

        // Create HTTP client
        httpClient = new HttpClient();
        httpClient.start();
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        if (httpClient != null)
            httpClient.stop();
        if (mockAcmeServer != null)
            mockAcmeServer.stop();
    }

    @Test
    public void testFetchDirectory() throws Exception
    {
        AcmeClient client = new AcmeClient(httpClient, accountKeyPair, directoryUrl);

        client.fetchDirectory();

        // Should not throw - directory was fetched
    }

    @Test
    public void testFetchNonce() throws Exception
    {
        AcmeClient client = new AcmeClient(httpClient, accountKeyPair, directoryUrl);
        client.fetchDirectory();

        String nonce = client.fetchNonce();

        assertThat(nonce, is(notNullValue()));
        assertThat(nonce, is("mock-nonce-12345"));
    }

    @Test
    public void testComputeKeyAuthorization() throws Exception
    {
        AcmeClient client = new AcmeClient(httpClient, accountKeyPair, directoryUrl);

        String token = "test-token-xyz";
        String keyAuth = client.computeKeyAuthorization(token);

        assertThat(keyAuth, is(notNullValue()));
        assertThat(keyAuth.startsWith(token + "."), is(true));
    }

    @Test
    public void testFetchDirectoryFailure() throws Exception
    {
        AcmeClient client = new AcmeClient(httpClient, accountKeyPair,
            "http://localhost:" + connector.getLocalPort() + "/invalid");

        assertThrows(AcmeException.class, client::fetchDirectory);
    }

    /**
     * Mock ACME server handler for testing.
     */
    private static class MockAcmeHandler extends Handler.Abstract.NonBlocking
    {
        @Override
        public boolean handle(Request request, Response response, Callback callback)
        {
            String path = request.getHttpURI().getPath();

            response.getHeaders().put("Replay-Nonce", "mock-nonce-12345");

            if ("/directory".equals(path))
            {
                String directoryJson = """
                    {
                        "newNonce": "http://localhost:%d/new-nonce",
                        "newAccount": "http://localhost:%d/new-account",
                        "newOrder": "http://localhost:%d/new-order",
                        "revokeCert": "http://localhost:%d/revoke-cert"
                    }
                    """.formatted(
                    getPort(request), getPort(request), getPort(request), getPort(request)
                );

                response.setStatus(HttpStatus.OK_200);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "application/json");
                response.write(true, ByteBuffer.wrap(directoryJson.getBytes()), callback);
                return true;
            }
            else if ("/new-nonce".equals(path))
            {
                response.setStatus(HttpStatus.OK_200);
                callback.succeeded();
                return true;
            }
            else if ("/new-account".equals(path))
            {
                String accountJson = """
                    {
                        "status": "valid",
                        "contact": ["mailto:test@example.com"]
                    }
                    """;

                response.setStatus(HttpStatus.CREATED_201);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "application/json");
                response.getHeaders().put("Location", "http://localhost:" + getPort(request) + "/acct/12345");
                response.write(true, ByteBuffer.wrap(accountJson.getBytes()), callback);
                return true;
            }

            response.setStatus(HttpStatus.NOT_FOUND_404);
            callback.succeeded();
            return true;
        }

        private int getPort(Request request)
        {
            return Request.getLocalPort(request);
        }
    }
}
