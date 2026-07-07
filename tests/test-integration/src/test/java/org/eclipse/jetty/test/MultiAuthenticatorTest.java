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

package org.eclipse.jetty.test;

import java.io.PrintWriter;
import java.net.URI;
import java.nio.file.Path;
import java.util.Map;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.FormRequestContent;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.security.AnyUserLoginService;
import org.eclipse.jetty.security.AuthenticationState;
import org.eclipse.jetty.security.Constraint;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.security.MultiAuthenticator;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.security.authentication.FormAuthenticator;
import org.eclipse.jetty.security.openid.OpenIdAuthenticator;
import org.eclipse.jetty.security.openid.OpenIdConfiguration;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.Session;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.session.SessionHandler;
import org.eclipse.jetty.tests.OpenIdProvider;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.eclipse.jetty.security.MultiAuthenticator.AUTH_TYPE_ATTR;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class MultiAuthenticatorTest
{
    private Server _server;
    private ServerConnector _connector;
    private HttpClient _client;
    private OpenIdProvider _provider;

    @BeforeEach
    public void before() throws Exception
    {
        // Set up a local OIDC provider and add its configuration to the Server.
        _provider = new OpenIdProvider();
        _provider.start();

        _server = new Server();
        _connector = new ServerConnector(_server);
        _server.addConnector(_connector);

        OpenIdConfiguration config = new OpenIdConfiguration.Builder(_provider.getProvider(), _provider.getClientId(), _provider.getClientSecret()).build();
        _server.addBean(config);

        ContextHandlerCollection handler = new ContextHandlerCollection();
        handler.addHandler(new AuthTestHandler("/"));

        SecurityHandler.PathMapped securityHandler = new SecurityHandler.PathMapped();
        securityHandler.put("/logout", Constraint.ALLOWED);
        securityHandler.put("/", Constraint.ANY_USER);
        securityHandler.setHandler(handler);

        MultiAuthenticator multiAuthenticator = new MultiAuthenticator();
        multiAuthenticator.setLoginPath("/login");

        OpenIdAuthenticator openIdAuthenticator = new OpenIdAuthenticator(config, "/error");
        openIdAuthenticator.setRedirectPath("/redirect_path");
        openIdAuthenticator.setLogoutRedirectPath("/");
        multiAuthenticator.addAuthenticator("/login/openid", openIdAuthenticator);

        Path fooPropsFile = MavenTestingUtils.getTestResourcePathFile("user.properties");
        Resource fooResource = ResourceFactory.root().newResource(fooPropsFile);
        HashLoginService loginService = new HashLoginService("users", fooResource);
        _server.addBean(loginService);
        FormAuthenticator formAuthenticator = new FormAuthenticator("/login/form", "/error", false);
        formAuthenticator.setLoginService(loginService);
        multiAuthenticator.addAuthenticator("/login/form", formAuthenticator);

        securityHandler.setAuthenticator(multiAuthenticator);
        securityHandler.setLoginService(new AnyUserLoginService(_provider.getProvider(), null));
        SessionHandler sessionHandler = new SessionHandler();
        sessionHandler.setHandler(securityHandler);
        ContextHandler contextHandler = new ContextHandler();
        contextHandler.setContextPath("/");
        contextHandler.setHandler(sessionHandler);

        _server.setHandler(contextHandler);
        _server.start();
        String redirectUri = "http://localhost:" + _connector.getLocalPort() + "/redirect_path";
        _provider.addRedirectUri(redirectUri);

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
    public void testMultiAuthentication() throws Exception
    {
        // Initial request gets the MultiAuthenticator login page because / is protected.
        URI uri = URI.create("http://localhost:" + _connector.getLocalPort());
        ContentResponse response = _client.GET(uri);
        assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
        assertThat(response.getContentAsString(), containsString("<h1>Multi Login Page</h1>"));
        assertThat(response.getContentAsString(), containsString("/login/openid"));
        assertThat(response.getContentAsString(), containsString("/login/form"));
        assertThat(response.getContentAsString(), containsString("authType: null"));

        // Requesting a FORM protected page redirects to the login form.
        response = _client.GET(uri.resolve("/login/form"));
        assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
        assertThat(response.getContentAsString(), containsString("<form action=\"j_security_check\" method=\"POST\">"));

        // Submitting FORM login is successful.
        Fields fields = new Fields();
        fields.put("j_username", "user");
        fields.put("j_password", "password");
        response = _client.POST(uri.resolve("/j_security_check"))
            .body(new FormRequestContent(fields))
            .send();
        assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
        assertThat(response.getContentAsString(), containsString("userPrincipal: user"));
        assertThat(response.getContentAsString(), containsString("MultiAuthenticator$MultiSucceededAuthenticationState"));
        assertThat(response.getContentAsString(), containsString("authType: FORM"));

        // After logout we are redirected to the MultiAuth login page as we cannot access protected resource.
        response = _client.GET(uri.resolve("/logout"));
        assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
        assertThat(response.getContentAsString(), containsString("<h1>Multi Login Page</h1>"));
        assertThat(response.getContentAsString(), containsString("/login/openid"));
        assertThat(response.getContentAsString(), containsString("/login/form"));
        assertThat(response.getContentAsString(), containsString("authType: null"));

        // We can now log in with OpenID.
        _provider.setUser(new OpenIdProvider.User("UserId1234", "openIdUser"));
        response = _client.GET(uri.resolve("/login/openid"));
        assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
        assertThat(response.getContentAsString(), containsString("userPrincipal: UserId1234"));
        assertThat(response.getContentAsString(), containsString("Authenticated with OpenID"));
        assertThat(response.getContentAsString(), containsString("name: openIdUser"));
        assertThat(response.getContentAsString(), containsString("authType: OPENID"));

        // Logout is successful.
        response = _client.GET(uri.resolve("/logout"));
        assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
        assertThat(response.getContentAsString(), containsString("<h1>Multi Login Page</h1>"));
        assertThat(response.getContentAsString(), containsString("/login/openid"));
        assertThat(response.getContentAsString(), containsString("/login/form"));
        assertThat(response.getContentAsString(), containsString("authType: null"));
    }

    private static AuthenticationState.Succeeded getAuthentication(Request request)
    {
        AuthenticationState authenticationState = AuthenticationState.getAuthenticationState(request);
        AuthenticationState.Succeeded auth = null;
        if (authenticationState instanceof AuthenticationState.Succeeded succeeded)
            auth = succeeded;
        else if (authenticationState instanceof AuthenticationState.Deferred deferred)
            auth = deferred.authenticate(request);
        return auth;
    }

    private static class AuthTestHandler extends ContextHandler
    {
        public AuthTestHandler(String contextPath)
        {
            super(contextPath);
        }

        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            String pathInContext = Request.getPathInContext(request);
            if (pathInContext.startsWith("/error"))
                return onError(request, response, callback);
            else if (pathInContext.startsWith("/logout"))
                return onLogout(request, response, callback);
            else if (pathInContext.equals("/login"))
                return onLogin(request, response, callback);
            else if (pathInContext.startsWith("/login/form"))
                return onFormLogin(request, response, callback);
            else if (pathInContext.startsWith("/login/openid"))
                return onOpenIdLogin(request, response, callback);

            Session session = request.getSession(false);
            assertNotNull(session);

            try (PrintWriter writer = new PrintWriter(Content.Sink.asOutputStream(response)))
            {
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/html");
                writer.println("<b>authType: " + session.getAttribute(AUTH_TYPE_ATTR) + "</b><br>");
                AuthenticationState.Succeeded auth = getAuthentication(request);
                if (auth != null)
                {
                    writer.println("<b>authState: " + auth + "</b><br>");
                    writer.println("<b>userPrincipal: " + auth.getUserPrincipal() + "</b><br>");

                    @SuppressWarnings("unchecked")
                    Map<String, Object> claims = (Map<String, Object>)session.getAttribute(OpenIdAuthenticator.CLAIMS);
                    if (claims != null)
                    {
                        writer.printf("""
                            <br><b>Authenticated with OpenID</b><br>
                            userId: %s<br>
                            name: %s<br>
                            email: %s<br>
                            """, claims.get("sub"), claims.get("name"), claims.get("email"));
                    }

                    writer.println("""
                        <hr>
                        <a href="/logout">Logout</a><br>
                        """);
                }
            }

            callback.succeeded();
            return true;
        }

        private boolean onLogin(Request request, Response response, Callback callback) throws Exception
        {
            AuthenticationState.Succeeded authentication = getAuthentication(request);
            if (authentication != null)
            {
                Response.sendRedirect(request, response, callback, "/");
                return true;
            }

            Session session = request.getSession(false);
            String authType = (session == null || session.getAttribute(AUTH_TYPE_ATTR) == null) ? "null" : (String)session.getAttribute(AUTH_TYPE_ATTR);
            String content = """
                        <h1>Multi Login Page</h1>
                        <a href="/login/openid">OpenID Login</a><br>
                        <a href="/login/form">Form Login</a><br>
                        <a href="/logout">Logout</a><br>
                        <b>authType: %s</b><br>
                        """.formatted(authType);
            response.write(true, BufferUtil.toReadableBuffer(content), callback);
            return true;
        }

        private boolean onOpenIdLogin(Request request, Response response, Callback callback) throws Exception
        {
            Response.sendRedirect(request, response, callback, "/");
            return true;
        }

        private boolean onFormLogin(Request request, Response response, Callback callback) throws Exception
        {
            AuthenticationState.Succeeded authentication = getAuthentication(request);
            if (authentication != null)
            {
                Response.sendRedirect(request, response, callback, "/");
                return true;
            }

            String content = """
                    <h2>Login</h2>
                    <form action="j_security_check" method="POST">
                        <div>
                            <label for="username">Username:</label>
                            <input type="text" id="username" name="j_username" required>
                        </div>
                        <div>
                            <label for="password">Password:</label>
                            <input type="password" id="password" name="j_password" required>
                        </div>
                        <div>
                            <button type="submit">Login</button>
                        </div>
                    </form>
                    <p>Username: user or admin<br>
                    Password: password</p>
                    """;
            response.write(true, BufferUtil.toReadableBuffer(content), callback);
            return true;
        }

        private boolean onLogout(Request request, Response response, Callback callback) throws Exception
        {
            Request.AuthenticationState authState = Request.getAuthenticationState(request);
            if (authState instanceof AuthenticationState.Succeeded succeeded)
                succeeded.logout(request, response);
            else if (authState instanceof AuthenticationState.Deferred deferred)
                deferred.logout(request, response);
            else
                request.getSession(true).invalidate();

            if (!response.isCommitted())
                Response.sendRedirect(request, response, callback, "/");
            else
                callback.succeeded();
            return true;
        }

        private boolean onError(Request request, Response response, Callback callback) throws Exception
        {
            Fields parameters = Request.getParameters(request);
            String errorDescription = parameters.getValue("error_description_jetty");
            response.write(true, BufferUtil.toReadableBuffer("error: " + errorDescription), callback);
            return true;
        }
    }
}
