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

package org.eclipse.jetty.security.siwe;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.MultiPartRequestContent;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MultiPart;
import org.eclipse.jetty.security.AuthenticationState;
import org.eclipse.jetty.security.Constraint;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.security.siwe.util.EthereumCredentials;
import org.eclipse.jetty.security.siwe.util.SignInWithEthereumGenerator;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.session.SessionHandler;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ajax.JSON;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SignInWithEthereumTest
{
    private final EthereumCredentials _credentials = new EthereumCredentials();
    private Server _server;
    private ServerConnector _connector;
    private EthereumAuthenticator _authenticator;
    private HttpClient _client;

    @BeforeEach
    public void before() throws Exception
    {
        _server = new Server();
        _connector = new ServerConnector(_server);
        _server.addConnector(_connector);

        Handler.Abstract handler = new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                String pathInContext = Request.getPathInContext(request);
                if ("/error".equals(pathInContext))
                {
                    response.setStatus(HttpStatus.FORBIDDEN_403);
                    String error = Request.getParameters(request).get(EthereumAuthenticator.ERROR_PARAMETER).getValue();
                    response.write(true, BufferUtil.toBuffer(error), callback);
                    return true;
                }
                if ("/login".equals(pathInContext))
                {
                    response.write(true, BufferUtil.toBuffer("Please Login"), callback);
                    return true;
                }
                else if ("/logout".equals(pathInContext))
                {
                    AuthenticationState.logout(request, response);
                    callback.succeeded();
                    return true;
                }

                AuthenticationState authState = Objects.requireNonNull(AuthenticationState.getAuthenticationState(request));
                response.write(true, BufferUtil.toBuffer("UserPrincipal: " + authState.getUserPrincipal()), callback);
                return true;
            }
        };

        _authenticator = new EthereumAuthenticator();
        _authenticator.setErrorPage("/error");

        SecurityHandler.PathMapped securityHandler = new SecurityHandler.PathMapped();
        securityHandler.setAuthenticator(_authenticator);
        securityHandler.setHandler(handler);
        securityHandler.setParameter(EthereumAuthenticator.LOGIN_PATH_PARAM, "/login");
        securityHandler.put("/*", Constraint.ANY_USER);

        SessionHandler sessionHandler = new SessionHandler();
        sessionHandler.setHandler(securityHandler);

        ContextHandler contextHandler = new ContextHandler();
        contextHandler.setContextPath("/");
        contextHandler.setHandler(sessionHandler);

        _server.setHandler(contextHandler);
        _server.start();

        _client = new HttpClient();
        _client.start();
    }

    @AfterEach
    public void after() throws Exception
    {
        _client.stop();
        _server.stop();
    }

    @Test
    public void testLoginLogoutSequence() throws Exception
    {
        _client.setFollowRedirects(false);

        // Initial request redirects to /login.html
        ContentResponse response = _client.GET("http://localhost:" + _connector.getLocalPort() + "/admin");
        assertTrue(HttpStatus.isRedirection(response.getStatus()), "HttpStatus was not redirect: " + response.getStatus());
        assertThat(response.getHeaders().get(HttpHeader.LOCATION), equalTo("/login"));

        // Request to Login page bypasses security constraints.
        response = _client.GET("http://localhost:" + _connector.getLocalPort() + "/login");
        assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
        assertThat(response.getContentAsString(), equalTo("Please Login"));

        // We can get a nonce from the server without being logged in.
        String nonce = getNonce();

        // Create ethereum credentials to login, and sign a login message.
        String siweMessage = SignInWithEthereumGenerator.generateMessage(_connector.getLocalPort(), _credentials.getAddress(), nonce);
        EthereumAuthenticator.SignedMessage signedMessage = _credentials.signMessage(siweMessage);

        // Send an Authentication request with the signed SIWE message, this should redirect back to initial request.
        response = sendAuthRequest(signedMessage);
        assertTrue(HttpStatus.isRedirection(response.getStatus()), "HttpStatus was not redirect: " + response.getStatus());
        assertThat(response.getHeaders().get(HttpHeader.LOCATION), equalTo("/admin"));

        // Now we are logged in a request to /admin succeeds.
        response = _client.GET("http://localhost:" + _connector.getLocalPort() + "/admin");
        assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
        assertThat(response.getContentAsString(), equalTo("UserPrincipal: " + _credentials.getAddress()));

        // We are unauthenticated after logging out.
        response = _client.GET("http://localhost:" + _connector.getLocalPort() + "/logout");
        assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
        response = _client.GET("http://localhost:" + _connector.getLocalPort() + "/admin");
        assertTrue(HttpStatus.isRedirection(response.getStatus()), "HttpStatus was not redirect: " + response.getStatus());
        assertThat(response.getHeaders().get(HttpHeader.LOCATION), equalTo("/login"));
    }

    @Test
    public void testAuthRequestTooLarge() throws Exception
    {
        int maxMessageSize = 1024 * 4;
        _authenticator.setMaxMessageSize(maxMessageSize);

        MultiPartRequestContent content = new MultiPartRequestContent();
        String message = "x".repeat(maxMessageSize + 1);
        content.addPart(new MultiPart.ByteBufferPart("message", null, null, BufferUtil.toBuffer(message)));
        content.close();
        ContentResponse response = _client.newRequest("localhost", _connector.getLocalPort())
            .path("/auth/login")
            .method(HttpMethod.POST)
            .body(content)
            .send();

        assertThat(response.getStatus(), equalTo(HttpStatus.FORBIDDEN_403));
        assertThat(response.getContentAsString(), containsString("SIWE Message Too Large"));
    }

    @Test
    public void testInvalidNonce() throws Exception
    {
        ContentResponse response;
        String nonce = getNonce();

        // Create ethereum credentials to login, and sign a login message.
        String siweMessage = SignInWithEthereumGenerator.generateMessage(_connector.getLocalPort(), _credentials.getAddress(), nonce);
        EthereumAuthenticator.SignedMessage signedMessage = _credentials.signMessage(siweMessage);

        // Initial authentication should succeed because it has a valid nonce.
        response = sendAuthRequest(signedMessage);
        assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
        assertThat(response.getContentAsString(), equalTo("UserPrincipal: " + _credentials.getAddress()));

        // Ensure we are logged out.
        response = _client.GET("http://localhost:" + _connector.getLocalPort() + "/logout");
        assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
        response = _client.GET("http://localhost:" + _connector.getLocalPort() + "/admin");
        assertThat(response.getContentAsString(), equalTo("Please Login"));

        // Replay the exact same request, and it should now fail because the nonce is invalid.
        response = sendAuthRequest(signedMessage);
        assertThat(response.getStatus(), equalTo(HttpStatus.FORBIDDEN_403));
        assertThat(response.getContentAsString(), containsString("invalid nonce"));
    }

    @Test
    public void testEnforceDomain() throws Exception
    {
        _authenticator.includeDomains("example.com");

        // Test login with invalid domain.
        String nonce = getNonce();
        String siweMessage = SignInWithEthereumGenerator.generateMessage(null, "localhost", _credentials.getAddress(), nonce);
        ContentResponse response = sendAuthRequest(_credentials.signMessage(siweMessage));
        assertThat(response.getStatus(), equalTo(HttpStatus.FORBIDDEN_403));
        assertThat(response.getContentAsString(), containsString("unregistered domain"));

        // Test login with valid domain.
        nonce = getNonce();
        siweMessage = SignInWithEthereumGenerator.generateMessage(null, "example.com", _credentials.getAddress(), nonce);
        response = sendAuthRequest(_credentials.signMessage(siweMessage));
        assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
        assertThat(response.getContentAsString(), containsString("UserPrincipal: " + _credentials.getAddress()));
    }

    @Test
    public void testEnforceChainId() throws Exception
    {
        _authenticator.includeChainIds("1");

        // Test login with invalid chainId.
        String nonce = getNonce();
        String siweMessage = SignInWithEthereumGenerator.generateMessage(null, "localhost", _credentials.getAddress(), nonce, "2");
        ContentResponse response = sendAuthRequest(_credentials.signMessage(siweMessage));
        assertThat(response.getStatus(), equalTo(HttpStatus.FORBIDDEN_403));
        assertThat(response.getContentAsString(), containsString("unregistered chainId"));

        // Test login with valid chainId.
        nonce = getNonce();
        siweMessage = SignInWithEthereumGenerator.generateMessage(null, "localhost", _credentials.getAddress(), nonce, "1");
        response = sendAuthRequest(_credentials.signMessage(siweMessage));
        assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
        assertThat(response.getContentAsString(), containsString("UserPrincipal: " + _credentials.getAddress()));
    }

    private ContentResponse sendAuthRequest(EthereumAuthenticator.SignedMessage signedMessage) throws ExecutionException, InterruptedException, TimeoutException
    {
        MultiPartRequestContent content = new MultiPartRequestContent();
        content.addPart(new MultiPart.ByteBufferPart("signature", null, null, BufferUtil.toBuffer(signedMessage.signature())));
        content.addPart(new MultiPart.ByteBufferPart("message", null, null, BufferUtil.toBuffer(signedMessage.message())));
        content.close();
        return _client.newRequest("localhost", _connector.getLocalPort())
            .path("/auth/login")
            .method(HttpMethod.POST)
            .body(content)
            .send();
    }

    private String getNonce() throws ExecutionException, InterruptedException, TimeoutException
    {
        ContentResponse response = _client.GET("http://localhost:" + _connector.getLocalPort() + "/auth/nonce");
        assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = (Map<String, Object>)new JSON().parse(new JSON.StringSource(response.getContentAsString()));
        String nonce = (String)parsed.get("nonce");
        assertThat(nonce.length(), equalTo(8));

        return nonce;
    }
}
