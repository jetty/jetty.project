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
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.Session;
import org.eclipse.jetty.server.handler.ContextHandler;
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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

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

        OpenIdConfiguration config = new OpenIdConfiguration(_provider.getProvider(), _provider.getClientId(), _provider.getClientSecret());
        _server.addBean(config);

        SecurityHandler.PathMapped securityHandler = new SecurityHandler.PathMapped();
        securityHandler.put("", Constraint.ALLOWED);
        securityHandler.put("/logout", Constraint.ALLOWED);
        securityHandler.put("/", Constraint.ANY_USER);
        securityHandler.setHandler(new AuthTestHandler());

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
        URI uri = URI.create("http://localhost:" + _connector.getLocalPort());
        ContentResponse response = _client.GET(uri);
        assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
        assertThat(response.getContentAsString(), containsString("<h1>Multi Login Page</h1>"));
        assertThat(response.getContentAsString(), containsString("/login/openid"));
        assertThat(response.getContentAsString(), containsString("/login/form"));

        // Try Form Login.
        response = _client.GET(uri.resolve("/login/form"));
        assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
        assertThat(response.getContentAsString(), containsString("<form action=\"j_security_check\" method=\"POST\">"));

        // Form login is successful.
        Fields fields = new Fields();
        fields.put("j_username", "user");
        fields.put("j_password", "password");
        response = _client.POST(uri.resolve("/j_security_check"))
            .body(new FormRequestContent(fields))
            .send();
        assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
        assertThat(response.getContentAsString(), containsString("userPrincipal: user"));
        assertThat(response.getContentAsString(), containsString("MultiAuthenticator$MultiSucceededAuthenticationState"));

        // Logout is successful.
        response = _client.GET(uri.resolve("/logout"));
        assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
        assertThat(response.getContentAsString(), containsString("<h1>Multi Login Page</h1>"));
        assertThat(response.getContentAsString(), containsString("/login/openid"));
        assertThat(response.getContentAsString(), containsString("/login/form"));

        // We can now log in with OpenID.
        _provider.setUser(new OpenIdProvider.User("UserId1234", "openIdUser"));
        response = _client.GET(uri.resolve("/login/openid"));
        assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
        assertThat(response.getContentAsString(), containsString("userPrincipal: UserId1234"));
        assertThat(response.getContentAsString(), containsString("Authenticated with OpenID"));
        assertThat(response.getContentAsString(), containsString("name: openIdUser"));

        // Logout is successful.
        response = _client.GET(uri.resolve("/logout"));
        assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
        assertThat(response.getContentAsString(), containsString("<h1>Multi Login Page</h1>"));
        assertThat(response.getContentAsString(), containsString("/login/openid"));
        assertThat(response.getContentAsString(), containsString("/login/form"));
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

    private static class AuthTestHandler extends Handler.Abstract
    {
        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            String pathInContext = Request.getPathInContext(request);
            if (pathInContext.startsWith("/error"))
                return onError(request, response, callback);
            else if (pathInContext.startsWith("/logout"))
                return onLogout(request, response, callback);
            else if (pathInContext.startsWith("/login/form"))
                return onFormLogin(request, response, callback);
            else if (pathInContext.startsWith("/login/openid"))
                return onOpenIdLogin(request, response, callback);

            try (PrintWriter writer = new PrintWriter(Content.Sink.asOutputStream(response)))
            {

                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/html");
                AuthenticationState.Succeeded auth = getAuthentication(request);
                if (auth != null)
                {
                    writer.println("<b>authState: " + auth + "</b><br>");
                    writer.println("<b>userPrincipal: " + auth.getUserPrincipal() + "</b><br>");

                    Session session = request.getSession(true);
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
                else
                {
                    writer.println("""
                        <h1>Multi Login Page</h1>
                        <a href="/login/openid">OpenID Login</a><br>
                        <a href="/login/form">Form Login</a><br>
                        <a href="/logout">Logout</a><br>
                        """);
                }
            }

            callback.succeeded();
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
            response.write(true, BufferUtil.toBuffer(content), callback);
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
            response.write(true, BufferUtil.toBuffer("error: " + errorDescription), callback);
            return true;
        }
    }
}
