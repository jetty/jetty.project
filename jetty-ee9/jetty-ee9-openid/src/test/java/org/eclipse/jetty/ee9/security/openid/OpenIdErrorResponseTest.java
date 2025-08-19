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

package org.eclipse.jetty.ee9.security.openid;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.ee9.nested.ServletConstraint;
import org.eclipse.jetty.ee9.nested.SessionHandler;
import org.eclipse.jetty.ee9.security.ConstraintMapping;
import org.eclipse.jetty.ee9.security.ConstraintSecurityHandler;
import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.security.Authenticator;
import org.eclipse.jetty.security.openid.OpenIdConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.tests.OpenIdProvider;
import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.UrlEncoded;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SuppressWarnings("unchecked")
public class OpenIdErrorResponseTest
{
    private OpenIdProvider _provider;
    private Server _server;
    private ServerConnector _connector;
    private HttpClient _client;

    @BeforeEach
    public void setup() throws Exception
    {
        _server = new Server();
        _connector = new ServerConnector(_server);
        _server.addConnector(_connector);
        _provider = new OpenIdProvider();
        _provider.start();
        _server.addBean(new OpenIdConfiguration(_provider.getProvider(), _provider.getClientId(), _provider.getClientSecret()));

        // Configure security constraints.
        ServletConstraint constraint = new ServletConstraint();
        constraint.setName(org.eclipse.jetty.ee9.security.Authenticator.OPENID_AUTH);
        constraint.setRoles(new String[]{"**"});
        constraint.setAuthenticate(true);

        ServletConstraint adminConstraint = new ServletConstraint();
        adminConstraint.setName(org.eclipse.jetty.ee9.security.Authenticator.OPENID_AUTH);
        adminConstraint.setRoles(new String[]{"admin"});
        adminConstraint.setAuthenticate(true);

        ConstraintMapping profileMapping = new ConstraintMapping();
        profileMapping.setConstraint(constraint);
        profileMapping.setPathSpec("/profile");
        ConstraintMapping loginMapping = new ConstraintMapping();
        loginMapping.setConstraint(constraint);
        loginMapping.setPathSpec("/login");
        ConstraintMapping adminMapping = new ConstraintMapping();
        adminMapping.setConstraint(adminConstraint);
        adminMapping.setPathSpec("/admin");

        // Configure SecurityHandler.
        ConstraintSecurityHandler securityHandler = new ConstraintSecurityHandler();
        assertThat(securityHandler.getKnownAuthenticatorFactories().size(), greaterThanOrEqualTo(2));
        securityHandler.setAuthMethod(Authenticator.OPENID_AUTH);
        securityHandler.setRealmName(_provider.getProvider());
        securityHandler.addConstraintMapping(profileMapping);
        securityHandler.addConstraintMapping(loginMapping);
        securityHandler.addConstraintMapping(adminMapping);

        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.addServlet(TestServlet.class, "/*");
        contextHandler.insertHandler(new SessionHandler());
        contextHandler.insertHandler(securityHandler);
        _server.setHandler(contextHandler);

        // Configure Jetty to use the local OIDC provider we have previously configured.
        securityHandler.setInitParameter(OpenIdAuthenticator.REDIRECT_PATH, "/redirect_path");
        securityHandler.setInitParameter(OpenIdAuthenticator.ERROR_PAGE, "/error");
        securityHandler.setInitParameter(OpenIdAuthenticator.LOGOUT_REDIRECT_PATH, "/");

        // Start the server and add the Servers RedirectURI to the Provider.
        _server.start();
        _provider.addRedirectUri("http://localhost:" + _connector.getLocalPort() + "/redirect_path");

        _client = new HttpClient();
        _client.start();
    }

    @AfterEach
    public void stop() throws Exception
    {
        _client.stop();
        _provider.stop();
        _server.stop();
    }

    public static class TestServlet extends HttpServlet
    {
        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            try (PrintWriter out = response.getWriter())
            {
                response.setStatus(HttpStatus.OK_200);
                response.setHeader(HttpHeader.CONTENT_TYPE.asString(), "text/plain");

                String pathInContext = URIUtil.addPaths(request.getServletPath(), request.getPathInfo());
                if (pathInContext.startsWith("/login"))
                {
                    if (request.getUserPrincipal() == null)
                        throw new IllegalStateException("No authentication state");
                    out.println("login success");
                }
                else if (pathInContext.startsWith("/logout"))
                {
                    request.logout();
                }
                else if (pathInContext.startsWith("/admin"))
                {
                    Map<String, Object> userInfo = (Map<String, Object>)request.getSession(false).getAttribute(org.eclipse.jetty.security.openid.OpenIdAuthenticator.CLAIMS);
                    out.println(userInfo.get("sub") + ": success");
                    out.close();
                }
                else if (pathInContext.startsWith("/error"))
                {
                    out.println("error page");
                }
                else
                {
                    if (request.getUserPrincipal() != null)
                    {
                        Map<String, Object> userInfo = (Map<String, Object>)request.getSession(false).getAttribute(org.eclipse.jetty.security.openid.OpenIdAuthenticator.CLAIMS);
                        out.println("userId: " + userInfo.get("sub") + "<br>");
                        out.println("name: " + userInfo.get("name") + "<br>");
                        out.println("email: " + userInfo.get("email") + "<br>");
                    }
                    else
                    {
                        out.println("not authenticated");
                    }
                }
            }
        }
    }

    @Test
    public void testTokenEndpointError() throws Exception
    {
        String appUriString = "http://localhost:" + _connector.getLocalPort();
        _provider.setUser(new OpenIdProvider.User("123456789", "Alice"));

        // Request to log in, redirects to the authorization server.
        _client.setFollowRedirects(false);
        ContentResponse response = _client.GET(appUriString + "/login");
        assertThat(response.getStatus(), is(HttpStatus.SEE_OTHER_303));
        String location = response.getHeaders().get(HttpHeader.LOCATION);
        assertNotNull(location);

        // Follow the redirect to the authorization server to be logged in.
        response = _client.GET(location);
        assertThat(response.getStatus(), is(HttpStatus.MOVED_TEMPORARILY_302));
        location = response.getHeaders().get(HttpHeader.LOCATION);
        assertNotNull(location);

        // Make the provider forget about the auth code it issued.
        _provider.getIssuedAuthCodes().clear();

        // Jetty will try the token endpoint, get an error, then redirect to the error page.
        response = _client.GET(location);
        assertThat(response.getStatus(), is(HttpStatus.SEE_OTHER_303));
        location = response.getHeaders().get(HttpHeader.LOCATION);
        assertNotNull(location);

        // Test the error page query parameters.
        HttpURI.Mutable locationUri = HttpURI.build(location);
        assertThat(locationUri.getPath(), equalTo("/error"));
        MultiMap<String> queryParams = UrlEncoded.decodeQuery(locationUri.getQuery());
        assertThat(queryParams.getValue(org.eclipse.jetty.security.openid.OpenIdAuthenticator.ERROR_PARAMETER), startsWith("auth failed: "));
        assertThat(queryParams.getValue("error"), equalTo("invalid_grant"));
        assertThat(queryParams.getValue("error_description"), equalTo("bad auth code"));
    }

    @Test
    public void testAuthEndpointError() throws Exception
    {
        String appUriString = "http://localhost:" + _connector.getLocalPort();
        _provider.setUser(new OpenIdProvider.User("123456789", "Alice"));

        // Request to log in, redirects to the authorization server.
        _client.setFollowRedirects(false);
        ContentResponse response = _client.GET(appUriString + "/login");
        assertThat(response.getStatus(), is(HttpStatus.SEE_OTHER_303));
        String location = response.getHeaders().get(HttpHeader.LOCATION);
        assertNotNull(location);

        // Transform the location uri to remove the client_id parameter to generate an error on the authorization server.
        HttpURI.Mutable locationUri = HttpURI.build(location);
        MultiMap<String> queryParams = UrlEncoded.decodeQuery(locationUri.getQuery());
        queryParams.put("client_id", "bad_value");
        locationUri.query(UrlEncoded.encode(queryParams, StandardCharsets.UTF_8, false));
        location = locationUri.toString();

        // The authorization server will redirect back to Jetty with the error in the query parameters.
        response = _client.GET(location);
        assertThat(response.getStatus(), is(HttpStatus.MOVED_TEMPORARILY_302));
        location = response.getHeaders().get(HttpHeader.LOCATION);
        assertNotNull(location);

        // Jetty will then redirect to the error page.
        response = _client.GET(location);
        assertThat(response.getStatus(), is(HttpStatus.SEE_OTHER_303));
        location = response.getHeaders().get(HttpHeader.LOCATION);
        assertNotNull(location);

        // Test the error page query parameters.
        locationUri = HttpURI.build(location);
        assertThat(locationUri.getPath(), equalTo("/error"));
        queryParams = UrlEncoded.decodeQuery(locationUri.getQuery());
        assertThat(queryParams.getValue(OpenIdAuthenticator.ERROR_PARAMETER), startsWith("auth failed: "));
        assertThat(queryParams.getValue("error"), equalTo("invalid_request_object"));
        assertThat(queryParams.getValue("error_description"), equalTo("invalid client_id"));
        assertThat(queryParams.getValue("error_uri"), equalTo("example.com/client_id"));
    }
}
