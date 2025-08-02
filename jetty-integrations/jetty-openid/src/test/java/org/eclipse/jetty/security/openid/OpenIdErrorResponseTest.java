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

package org.eclipse.jetty.security.openid;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.security.AuthenticationState;
import org.eclipse.jetty.security.Authenticator;
import org.eclipse.jetty.security.Constraint;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.session.SessionHandler;
import org.eclipse.jetty.tests.OpenIdProvider;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.MultiMap;
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

        // Configure SecurityHandler.
        SecurityHandler.PathMapped securityHandler = new SecurityHandler.PathMapped();
        assertThat(securityHandler.getKnownAuthenticatorFactories().size(), greaterThanOrEqualTo(2));
        securityHandler.setAuthenticationType(Authenticator.OPENID_AUTH);
        securityHandler.setRealmName(_provider.getProvider());

        ContextHandler contextHandler = new ContextHandler("/");
        contextHandler.setHandler(new TestHandler());
        contextHandler.insertHandler(securityHandler);
        contextHandler.insertHandler(new SessionHandler());
        _server.setHandler(contextHandler);

        // Configure security constraints.
        securityHandler.put("/login", Constraint.ANY_USER);
        securityHandler.put("/profile", Constraint.ANY_USER);
        securityHandler.put("/admin", Constraint.from("admin"));

        // Configure Jetty to use the local OIDC provider we have previously configured.
        securityHandler.setParameter(OpenIdAuthenticator.REDIRECT_PATH, "/redirect_path");
        securityHandler.setParameter(OpenIdAuthenticator.ERROR_PAGE, "/error");
        securityHandler.setParameter(OpenIdAuthenticator.LOGOUT_REDIRECT_PATH, "/");

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

    public static class TestHandler extends Handler.Abstract
    {
        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            AuthenticationState authState = AuthenticationState.getUndeferredAuthenticationState(request);
            try (PrintStream out = new PrintStream(Content.Sink.asOutputStream(response)))
            {
                response.setStatus(HttpStatus.OK_200);
                response.getHeaders().add(HttpHeader.CONTENT_TYPE, "text/plain");

                String pathInContext = Request.getPathInContext(request);
                if (pathInContext.startsWith("/login"))
                {
                    if (authState == null)
                        throw new IllegalStateException("No authentication state");
                    out.println("login success");
                }
                else if (pathInContext.startsWith("/logout"))
                {
                    AuthenticationState.logout(request, response);
                }
                else if (pathInContext.startsWith("/admin"))
                {
                    Map<String, Object> userInfo = (Map<String, Object>)request.getSession(false).getAttribute(OpenIdAuthenticator.CLAIMS);
                    out.println(userInfo.get("sub") + ": success");
                    out.close();
                }
                else if (pathInContext.startsWith("/error"))
                {
                    out.println("error page");
                }
                else
                {
                    if (authState != null)
                    {
                        Map<String, Object> userInfo = (Map<String, Object>)request.getSession(false).getAttribute(OpenIdAuthenticator.CLAIMS);
                        out.println("userId: " + userInfo.get("sub") + "<br>");
                        out.println("name: " + userInfo.get("name") + "<br>");
                        out.println("email: " + userInfo.get("email") + "<br>");
                    }
                    else
                    {
                        out.println("not authenticated");
                    }
                }

                out.close();
                callback.succeeded();
            }
            catch (Throwable t)
            {
                callback.failed(t);
            }

            return true;
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
        assertThat(queryParams.getValue(OpenIdAuthenticator.ERROR_PARAMETER), startsWith("auth failed: "));
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
