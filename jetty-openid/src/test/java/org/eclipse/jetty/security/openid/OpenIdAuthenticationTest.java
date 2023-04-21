//
//  ========================================================================
//  Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

import java.io.File;
import java.io.IOException;
import java.security.Principal;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.security.AbstractLoginService;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.UserIdentity;
import org.eclipse.jetty.server.session.FileSessionDataStoreFactory;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jetty.util.security.Password;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;

public class OpenIdAuthenticationTest
{
    public static final String CLIENT_ID = "testClient101";
    public static final String CLIENT_SECRET = "secret37989798";

    private OpenIdProvider openIdProvider;
    private Server server;
    private ServerConnector connector;
    private HttpClient client;

    public void setup(LoginService loginService) throws Exception
    {
        setup(loginService, null);
    }

    public void setup(LoginService loginService, Consumer<OpenIdConfiguration> configure) throws Exception
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
        assertThat(securityHandler.getKnownAuthenticatorFactories().size(), greaterThanOrEqualTo(2));

        securityHandler.setAuthMethod(Constraint.__OPENID_AUTH);
        securityHandler.setRealmName(openIdProvider.getProvider());
        securityHandler.addConstraintMapping(profileMapping);
        securityHandler.addConstraintMapping(loginMapping);
        securityHandler.addConstraintMapping(adminMapping);

        // Authentication using local OIDC Provider
        OpenIdConfiguration openIdConfiguration = new OpenIdConfiguration(openIdProvider.getProvider(), CLIENT_ID, CLIENT_SECRET);
        if (configure != null)
            configure.accept(openIdConfiguration);
        securityHandler.setLoginService(new OpenIdLoginService(openIdConfiguration, loginService));
        server.addBean(openIdConfiguration);
        securityHandler.setInitParameter(OpenIdAuthenticator.ERROR_PAGE, "/error");
        context.setSecurityHandler(securityHandler);

        File datastoreDir = MavenTestingUtils.getTargetTestingDir("datastore");
        IO.delete(datastoreDir);
        FileSessionDataStoreFactory fileSessionDataStoreFactory = new FileSessionDataStoreFactory();
        fileSessionDataStoreFactory.setStoreDir(datastoreDir);
        server.addBean(fileSessionDataStoreFactory);

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
        setup(null);
        openIdProvider.setUser(new OpenIdProvider.User("123456789", "Alice"));

        String appUriString = "http://localhost:" + connector.getLocalPort();

        // Initially not authenticated
        ContentResponse response = client.GET(appUriString + "/");
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        String content = response.getContentAsString();
        assertThat(content, containsString("not authenticated"));

        // Request to login is success
        response = client.GET(appUriString + "/login");
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        content = response.getContentAsString();
        assertThat(content, containsString("success"));

        // Now authenticated we can get info
        response = client.GET(appUriString + "/");
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        content = response.getContentAsString();
        assertThat(content, containsString("userId: 123456789"));
        assertThat(content, containsString("name: Alice"));
        assertThat(content, containsString("email: Alice@example.com"));

        // Request to admin page gives 403 as we do not have admin role
        response = client.GET(appUriString + "/admin");
        assertThat(response.getStatus(), is(HttpStatus.FORBIDDEN_403));

        // We can restart the server and still be logged in as we have persistent session datastore.
        server.stop();
        server.start();
        appUriString = "http://localhost:" + connector.getLocalPort();

        // After restarting server the authentication is saved as a session authentication.
        response = client.GET(appUriString + "/");
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        content = response.getContentAsString();
        assertThat(content, containsString("userId: 123456789"));
        assertThat(content, containsString("name: Alice"));
        assertThat(content, containsString("email: Alice@example.com"));

        // We are no longer authenticated after logging out
        response = client.GET(appUriString + "/logout");
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        content = response.getContentAsString();
        assertThat(content, containsString("not authenticated"));

        // Test that the user was logged out successfully on the openid provider.
        assertThat(openIdProvider.getLoggedInUsers().getMax(), equalTo(1L));
        assertThat(openIdProvider.getLoggedInUsers().getTotal(), equalTo(1L));
    }

    @Test
    public void testNestedLoginService() throws Exception
    {
        AtomicBoolean loggedIn = new AtomicBoolean(true);
        setup(new AbstractLoginService()
        {
            @Override
            protected String[] loadRoleInfo(UserPrincipal user)
            {
                return new String[]{"admin"};
            }

            @Override
            protected UserPrincipal loadUserInfo(String username)
            {
                return new UserPrincipal(username, new Password(""));
            }

            @Override
            public boolean validate(UserIdentity user)
            {
                if (!loggedIn.get())
                    return false;
                return super.validate(user);
            }
        });

        openIdProvider.setUser(new OpenIdProvider.User("123456789", "Alice"));

        String appUriString = "http://localhost:" + connector.getLocalPort();

        // Initially not authenticated
        ContentResponse response = client.GET(appUriString + "/");
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        String content = response.getContentAsString();
        assertThat(content, containsString("not authenticated"));

        // Request to login is success
        response = client.GET(appUriString + "/login");
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        content = response.getContentAsString();
        assertThat(content, containsString("success"));

        // Now authenticated we can get info
        response = client.GET(appUriString + "/");
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        content = response.getContentAsString();
        assertThat(content, containsString("userId: 123456789"));
        assertThat(content, containsString("name: Alice"));
        assertThat(content, containsString("email: Alice@example.com"));

        // The nested login service has supplied the admin role.
        response = client.GET(appUriString + "/admin");
        assertThat(response.getStatus(), is(HttpStatus.OK_200));

        // This causes any validation of UserIdentity in the LoginService to fail
        // causing subsequent requests to be redirected to the auth endpoint for login again.
        loggedIn.set(false);
        client.setFollowRedirects(false);
        response = client.GET(appUriString + "/admin");
        assertThat(response.getStatus(), is(HttpStatus.SEE_OTHER_303));
        String location = response.getHeaders().get(HttpHeader.LOCATION);
        assertThat(location, containsString(openIdProvider.getProvider() + "/auth"));

        // Note that we couldn't follow "OpenID Connect RP-Initiated Logout 1.0" because we redirect straight to auth endpoint.
        assertThat(openIdProvider.getLoggedInUsers().getCurrent(), equalTo(1L));
        assertThat(openIdProvider.getLoggedInUsers().getMax(), equalTo(1L));
        assertThat(openIdProvider.getLoggedInUsers().getTotal(), equalTo(1L));
    }

    public static class LoginPage extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException
        {
            response.setContentType("text/html");
            response.getWriter().println("success");
            response.getWriter().println("<br><a href=\"/\">Home</a>");
        }
    }

    public static class LogoutPage extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            request.logout();
            response.sendRedirect("/");
        }
    }

    public static class AdminPage extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException
        {
            Map<String, Object> userInfo = (Map<String, Object>)request.getSession().getAttribute(OpenIdAuthenticator.CLAIMS);
            response.getWriter().println(userInfo.get("sub") + ": success");
        }
    }

    public static class HomePage extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException
        {
            response.setContentType("text/html");
            Principal userPrincipal = request.getUserPrincipal();
            if (userPrincipal != null)
            {
                Map<String, Object> userInfo = (Map<String, Object>)request.getSession().getAttribute(OpenIdAuthenticator.CLAIMS);
                response.getWriter().println("userId: " + userInfo.get("sub") + "<br>");
                response.getWriter().println("name: " + userInfo.get("name") + "<br>");
                response.getWriter().println("email: " + userInfo.get("email") + "<br>");
                response.getWriter().println("<br><a href=\"/logout\">Logout</a>");
            }
            else
            {
                response.getWriter().println("not authenticated");
                response.getWriter().println("<br><a href=\"/login\">Login</a>");
            }
        }
    }

    public static class ErrorPage extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException
        {
            response.setContentType("text/html");
            response.getWriter().println("not authorized");
            response.getWriter().println("<br><a href=\"/\">Home</a>");
        }
    }
}
