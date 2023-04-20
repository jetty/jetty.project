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

package org.eclipse.jetty.security;

import java.util.Arrays;

import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.ForwardedRequestCustomizer;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.session.SessionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

public class BasicAuthenticatorTest
{
    private Server _server;
    private LocalConnector _connector;
    private LocalConnector _connectorS;
    private SessionHandler _sessionHandler;
    private SecurityHandler.PathMapped _securityHandler;

    @BeforeEach
    public void configureServer() throws Exception
    {
        _server = new Server();

        _server.addBean(new AuthenticationTestHandler.CustomLoginService(new AuthenticationTestHandler.TestIdentityService()));

        HttpConnectionFactory http = new HttpConnectionFactory();
        HttpConfiguration httpConfiguration = http.getHttpConfiguration();
        httpConfiguration.setSecurePort(9999);
        httpConfiguration.setSecureScheme("BWTP");
        httpConfiguration.addCustomizer(new ForwardedRequestCustomizer());
        _connector = new LocalConnector(_server, http);
        _connector.setIdleTimeout(300000);

        HttpConnectionFactory https = new HttpConnectionFactory();
        https.getHttpConfiguration().addCustomizer((request, responseHeaders) ->
        {
            HttpURI.Mutable uri = HttpURI.build(request.getHttpURI()).scheme(HttpScheme.HTTPS);
            return new Request.Wrapper(request)
            {
                @Override
                public HttpURI getHttpURI()
                {
                    return uri;
                }

                @Override
                public boolean isSecure()
                {
                    return true;
                }
            };
        });

        _connectorS = new LocalConnector(_server, https);
        _server.setConnectors(new Connector[]{_connector, _connectorS});

        ContextHandler contextHandler = new ContextHandler();
        _sessionHandler = new SessionHandler();

        contextHandler.setContextPath("/ctx");
        _server.setHandler(contextHandler);
        contextHandler.setHandler(_sessionHandler);

        _securityHandler = new SecurityHandler.PathMapped();
        _sessionHandler.setHandler(_securityHandler);

        _securityHandler.put("/admin/*", Constraint.from("admin"));
        _securityHandler.put("/any/*", Constraint.ANY_USER);
        _securityHandler.put("/known/*", Constraint.KNOWN_ROLE);
        _securityHandler.setAuthenticator(new BasicAuthenticator());

        _securityHandler.setHandler(new AuthenticationTestHandler());
        _server.start();
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        if (_server.isRunning())
        {
            _server.stop();
            _server.join();
        }
    }

    @Test
    public void testBasic() throws Exception
    {
        String response;
        response = _connector.getResponse("GET /ctx/some/thing HTTP/1.0\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("Deferred"));

        response = _connector.getResponse("GET /ctx/some/thing HTTP/1.0\r\nAuthorization: %s\r\n\r\n"
            .formatted(BasicAuthenticator.authorization("wrong", "user")));
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("Deferred"));

        response = _connector.getResponse("GET /ctx/any/user HTTP/1.0\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 401 Unauthorized"));
        assertThat(response, not(containsString("OK")));

        response = _connector.getResponse("GET /ctx/any/user HTTP/1.0\r\nAuthorization: %s\r\n\r\n".formatted(BasicAuthenticator.authorization("wrong", "user")));
        assertThat(response, containsString("HTTP/1.1 401 Unauthorized"));
        assertThat(response, not(containsString("OK")));

        response = _connector.getResponse("GET /ctx/any/user HTTP/1.0\r\nAuthorization: %s\r\n\r\n".formatted(BasicAuthenticator.authorization("user", "password")));
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("user is OK"));

        response = _connector.getResponse("GET /ctx/any/user HTTP/1.0\r\nAuthorization: %s\r\n\r\n".formatted(BasicAuthenticator.authorization("admin", "password")));
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("admin is OK"));

        response = _connector.getResponse("GET /ctx/admin/user HTTP/1.0\r\nAuthorization: %s\r\n\r\n".formatted(BasicAuthenticator.authorization("user", "password")));
        assertThat(response, containsString("HTTP/1.1 403 Forbidden"));
        assertThat(response, containsString("!authorized"));
        assertThat(response, not(containsString("OK")));

        response = _connector.getResponse("GET /ctx/admin/user HTTP/1.0\r\nAuthorization: %s\r\n\r\n".formatted(BasicAuthenticator.authorization("admin", "password")));
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("admin is OK"));

        response = _connector.getResponse("GET /ctx/known/user HTTP/1.0\r\nAuthorization: %s\r\n\r\n".formatted(BasicAuthenticator.authorization("user", "password")));
        assertThat(response, containsString("HTTP/1.1 403 Forbidden"));
        assertThat(response, containsString("!authorized"));
        assertThat(response, not(containsString("OK")));
    }

    @Test
    public void testDeferredAuthenticate() throws Exception
    {
        HttpTester.Response response;

        response = HttpTester.parseResponse(_connector.getResponse(
            "GET /ctx/some/thing?action=authenticate HTTP/1.0\r\n\r\n"));
        assertThat(response.getStatus(), equalTo(200));
        assertThat(Arrays.stream(response.getContent().split(",")).toList(),
            contains("-", "Deferred"));

        response = HttpTester.parseResponse(_connector.getResponse(
            "GET /ctx/some/thing?action=authenticate HTTP/1.0\r\nAuthorization: %s\r\n\r\n"
                .formatted(BasicAuthenticator.authorization("user", "wrong"))));
        assertThat(response.getStatus(), equalTo(200));
        assertThat(Arrays.stream(response.getContent().split(",")).toList(),
            contains("-", "Deferred"));

        response = HttpTester.parseResponse(_connector.getResponse(
            "GET /ctx/some/thing?action=authenticate HTTP/1.0\r\nAuthorization: %s\r\n\r\n"
                .formatted(BasicAuthenticator.authorization("user", "password"))));
        assertThat(response.getStatus(), equalTo(200));
        assertThat(Arrays.stream(response.getContent().split(",")).toList(),
            contains("user", "user is OK"));

        response = HttpTester.parseResponse(_connector.getResponse(
            "GET /ctx/any/user?action=logout&action=authenticate HTTP/1.0\r\nAuthorization: %s\r\n\r\n"
                .formatted(BasicAuthenticator.authorization("user", "password"))));
        assertThat(response.getStatus(), equalTo(200));
        assertThat(Arrays.stream(response.getContent().split(",")).toList(),
            contains("true", "-", "Deferred"));
    }

    @Test
    public void testDeferredAuthenticateOrChallenge() throws Exception
    {
        HttpTester.Response response;

        response = HttpTester.parseResponse(_connector.getResponse(
            "GET /ctx/some/thing?action=challenge HTTP/1.0\r\n\r\n"));
        assertThat(response.getStatus(), equalTo(401));

        response = HttpTester.parseResponse(_connector.getResponse(
            "GET /ctx/some/thing?action=challenge HTTP/1.0\r\nAuthorization: %s\r\n\r\n"
                .formatted(BasicAuthenticator.authorization("user", "wrong"))));
        assertThat(response.getStatus(), equalTo(401));

        response = HttpTester.parseResponse(_connector.getResponse(
            "GET /ctx/some/thing?action=challenge HTTP/1.0\r\nAuthorization: %s\r\n\r\n"
                .formatted(BasicAuthenticator.authorization("user", "password"))));
        assertThat(response.getStatus(), equalTo(200));
        assertThat(Arrays.stream(response.getContent().split(",")).toList(),
            contains("user", "user is OK"));

        response = HttpTester.parseResponse(_connector.getResponse(
            "GET /ctx/any/user?action=logout&action=challenge HTTP/1.0\r\nAuthorization: %s\r\n\r\n"
                .formatted(BasicAuthenticator.authorization("user", "password"))));
        assertThat(response.getStatus(), equalTo(403));
    }

    @Test
    public void testLogin() throws Exception
    {
        HttpTester.Response response;

        response = HttpTester.parseResponse(_connector.getResponse(
            "GET /ctx/some/thing?action=login&username=user&password=wrong HTTP/1.0\r\n\r\n"));
        assertThat(response.getStatus(), equalTo(200));
        assertThat(Arrays.stream(response.getContent().split(",")).toList(),
            contains("-", "Deferred"));

        response = HttpTester.parseResponse(_connector.getResponse(
            "GET /ctx/some/thing?action=login&username=user&password=password HTTP/1.0\r\n\r\n"));
        assertThat(response.getStatus(), equalTo(200));
        assertThat(Arrays.stream(response.getContent().split(",")).toList(),
            contains("user", "user is OK"));

        response = HttpTester.parseResponse(_connector.getResponse(
            "GET /ctx/some/thing?action=login&username=user&password=password&action=logout&action=login&username=admin&password=password HTTP/1.0\r\n\r\n"));
        assertThat(response.getStatus(), equalTo(200));
        assertThat(Arrays.stream(response.getContent().split(",")).toList(),
            contains("user", "true", "admin", "admin is OK"));

        response = HttpTester.parseResponse(_connector.getResponse(
            "GET /ctx/any/user?action=login&username=admin&password=password HTTP/1.0\r\nAuthorization: %s\r\n\r\n"
                .formatted(BasicAuthenticator.authorization("user", "password"))));
        assertThat(response.getStatus(), equalTo(500));
    }

    @Test
    public void testIdentity() throws Exception
    {
        HttpTester.Response response;

        response = HttpTester.parseResponse(_connector.getResponse(
            "GET /ctx/some/thing?action=thread HTTP/1.0\r\n\r\n"));
        assertThat(response.getStatus(), equalTo(200));
        assertThat(Arrays.stream(response.getContent().split(",")).toList(),
            contains("null", "Deferred"));

        response = HttpTester.parseResponse(_connector.getResponse(
            "GET /ctx/any/user?action=thread HTTP/1.0\r\nAuthorization: %s\r\n\r\n"
                .formatted(BasicAuthenticator.authorization("user", "password"))));
        assertThat(response.getStatus(), equalTo(200));
        assertThat(Arrays.stream(response.getContent().split(",")).toList(),
            contains("user", "user is OK"));

        response = HttpTester.parseResponse(_connector.getResponse(
            "GET /ctx/any/user?action=thread&action=logout&action=thread HTTP/1.0\r\nAuthorization: %s\r\n\r\n"
                .formatted(BasicAuthenticator.authorization("user", "password"))));
        assertThat(response.getStatus(), equalTo(200));
        assertThat(Arrays.stream(response.getContent().split(",")).toList(),
            contains("user", "true", "null", "Deferred"));
    }
}
