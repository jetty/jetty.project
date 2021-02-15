//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.security.openid;

import java.io.IOException;
import java.security.Principal;
import java.util.Map;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.security.Authenticator;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.security.Constraint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class OpenIdAuthenticationTest
{
    public static final String CLIENT_ID = "testClient101";
    public static final String CLIENT_SECRET = "secret37989798";

    private OpenIdProvider openIdProvider;
    private Server server;
    private ServerConnector connector;
    private HttpClient client;

    @BeforeEach
    public void setup() throws Exception
    {
        openIdProvider = new OpenIdProvider(CLIENT_ID, CLIENT_SECRET);
        openIdProvider.start();

        server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);
        ServletContextHandler context = new ServletContextHandler(server, "/", ServletContextHandler.SESSIONS);

        // Add servlets
        context.addServlet(LoginPage.class, "/login");
        context.addServlet(LogoutPage.class, "/logout");
        context.addServlet(HomePage.class, "/*");
        context.addServlet(ErrorPage.class, "/error");

        // configure security constraints
        Constraint constraint = new Constraint();
        constraint.setName(Constraint.__OPENID_AUTH);
        constraint.setRoles(new String[]{"**"});
        constraint.setAuthenticate(true);

        Constraint adminConstraint = new Constraint();
        adminConstraint.setName(Constraint.__OPENID_AUTH);
        adminConstraint.setRoles(new String[]{"admin"});
        adminConstraint.setAuthenticate(true);

        // constraint mappings
        ConstraintMapping profileMapping = new ConstraintMapping();
        profileMapping.setConstraint(constraint);
        profileMapping.setPathSpec("/profile");
        ConstraintMapping loginMapping = new ConstraintMapping();
        loginMapping.setConstraint(constraint);
        loginMapping.setPathSpec("/login");
        ConstraintMapping adminMapping = new ConstraintMapping();
        adminMapping.setConstraint(adminConstraint);
        adminMapping.setPathSpec("/admin");

        // security handler
        ConstraintSecurityHandler securityHandler = new ConstraintSecurityHandler();
        securityHandler.setRealmName("OpenID Connect Authentication");
        securityHandler.addConstraintMapping(profileMapping);
        securityHandler.addConstraintMapping(loginMapping);
        securityHandler.addConstraintMapping(adminMapping);

        // Authentication using local OIDC Provider
        OpenIdConfiguration configuration = new OpenIdConfiguration(openIdProvider.getProvider(), CLIENT_ID, CLIENT_SECRET);

        // Configure OpenIdLoginService optionally providing a base LoginService to provide user roles
        OpenIdLoginService loginService = new OpenIdLoginService(configuration);
        securityHandler.setLoginService(loginService);

        Authenticator authenticator = new OpenIdAuthenticator(configuration, "/error");
        securityHandler.setAuthenticator(authenticator);
        context.setSecurityHandler(securityHandler);

        server.start();
        String redirectUri = "http://localhost:" + connector.getLocalPort() + "/j_security_check";
        openIdProvider.addRedirectUri(redirectUri);

        client = new HttpClient();
        client.start();
    }

    @AfterEach
    public void stop() throws Exception
    {
        openIdProvider.stop();
        server.stop();
    }

    @Test
    public void testLoginLogout() throws Exception
    {
        String appUriString = "http://localhost:" + connector.getLocalPort();

        // Initially not authenticated
        ContentResponse response = client.GET(appUriString + "/");
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        String[] content = response.getContentAsString().split("[\r\n]+");
        assertThat(content.length, is(1));
        assertThat(content[0], is("not authenticated"));

        // Request to login is success
        response = client.GET(appUriString + "/login");
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        content = response.getContentAsString().split("[\r\n]+");
        assertThat(content.length, is(1));
        assertThat(content[0], is("success"));

        // Now authenticated we can get info
        response = client.GET(appUriString + "/");
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        content = response.getContentAsString().split("[\r\n]+");
        assertThat(content.length, is(3));
        assertThat(content[0], is("userId: 123456789"));
        assertThat(content[1], is("name: Alice"));
        assertThat(content[2], is("email: Alice@example.com"));

        // Request to admin page gives 403 as we do not have admin role
        response = client.GET(appUriString + "/admin");
        assertThat(response.getStatus(), is(HttpStatus.FORBIDDEN_403));

        // We are no longer authenticated after logging out
        response = client.GET(appUriString + "/logout");
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        content = response.getContentAsString().split("[\r\n]+");
        assertThat(content.length, is(1));
        assertThat(content[0], is("not authenticated"));
    }

    public static class LoginPage extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException
        {
            response.getWriter().println("success");
        }
    }

    public static class LogoutPage extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException
        {
            request.getSession().invalidate();
            response.sendRedirect("/");
        }
    }

    public static class AdminPage extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException
        {
            Map<String, Object> userInfo = (Map)request.getSession().getAttribute(OpenIdAuthenticator.CLAIMS);
            response.getWriter().println(userInfo.get("sub") + ": success");
        }
    }

    public static class HomePage extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException
        {
            response.setContentType("text/plain");
            Principal userPrincipal = request.getUserPrincipal();
            if (userPrincipal != null)
            {
                Map<String, Object> userInfo = (Map)request.getSession().getAttribute(OpenIdAuthenticator.CLAIMS);
                response.getWriter().println("userId: " + userInfo.get("sub"));
                response.getWriter().println("name: " + userInfo.get("name"));
                response.getWriter().println("email: " + userInfo.get("email"));
            }
            else
            {
                response.getWriter().println("not authenticated");
            }
        }
    }

    public static class ErrorPage extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException
        {
            response.setContentType("text/plain");
            response.getWriter().println("not authorized");
        }
    }
}
