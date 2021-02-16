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
import java.io.PrintWriter;
import java.security.Principal;
import java.util.Map;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.DynamicAuthenticator;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.security.authentication.FormAuthenticator;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.security.Constraint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

public class DynamicAuthenticationTest
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
        constraint.setName(Constraint.__DYNAMIC_AUTH);
        constraint.setRoles(new String[]{"**"});
        constraint.setAuthenticate(true);

        Constraint adminConstraint = new Constraint();
        adminConstraint.setName(Constraint.__DYNAMIC_AUTH);
        adminConstraint.setRoles(new String[]{"admin"});
        adminConstraint.setAuthenticate(true);

        // constraint mappings
        ConstraintMapping openIdMapping = new ConstraintMapping();
        openIdMapping.setConstraint(constraint);
        openIdMapping.setPathSpec("/openid/*");

        ConstraintMapping loginMapping = new ConstraintMapping();
        loginMapping.setConstraint(constraint);
        loginMapping.setPathSpec("/login");

        ConstraintMapping adminMapping = new ConstraintMapping();
        adminMapping.setConstraint(adminConstraint);
        adminMapping.setPathSpec("/admin");

        // security handler
        ConstraintSecurityHandler securityHandler = new ConstraintSecurityHandler();
        securityHandler.setRealmName("Dual Authentication");
        securityHandler.addConstraintMapping(openIdMapping);
        securityHandler.addConstraintMapping(loginMapping);
        securityHandler.addConstraintMapping(adminMapping);

        // Server wide Authentication Configuration.
        HashLoginService loginService = new HashLoginService();
        loginService.setConfig(MavenTestingUtils.getTestResourceFile("foo.properties").getAbsolutePath());
        OpenIdConfiguration configuration = new OpenIdConfiguration(openIdProvider.getProvider(), CLIENT_ID, CLIENT_SECRET);
        server.addBean(configuration);

        // Add the DynamicAuthenticator.
        DynamicAuthenticator dynamicAuthenticator = new DynamicAuthenticator();
        dynamicAuthenticator.addMapping("/*", new FormAuthenticator());
        dynamicAuthenticator.addMapping("/openid/*", new OpenIdAuthenticator(configuration), null);

        // Any init params will go to all authenticators, so multiple authenticator of same type must be configured explicitly.
        securityHandler.setInitParameter(OpenIdAuthenticator.REDIRECT_PATH, "/openid/auth");
        securityHandler.setInitParameter(FormAuthenticator.__FORM_LOGIN_PAGE, "/login");

        securityHandler.setAuthenticator(dynamicAuthenticator);
        securityHandler.setLoginService(loginService);
        context.setSecurityHandler(securityHandler);

        server.setHandler(context);
        server.start();
        String redirectUri = "http://localhost:" + connector.getLocalPort() + "/openid/auth";
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
    public void testOpenIdLogin() throws Exception
    {
        // TODO
    }

    @Disabled("work in progress")
    @Test
    public void testFormLogin() throws Exception
    {
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

        // We are no longer authenticated after logging out
        response = client.GET(appUriString + "/logout");
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        content = response.getContentAsString();
        assertThat(content, containsString("not authenticated"));
    }

    public static class OpenIdLoginPage extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException
        {
            response.sendRedirect("/");
        }
    }

    public static class LoginPage extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException
        {
            response.setContentType("text/html");
            PrintWriter writer = response.getWriter();
            writer.println("<form method=post action=\"j_security_check\" >");
            writer.println("<input type=\"text\"  name= \"j_username\" >");
            writer.println("<input type=\"password\"  name= \"j_password\" >");
            writer.println("<input type=\"submit\" value=\"Submit\">");
            writer.println("</form>");
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
            @SuppressWarnings("unchecked")
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
            PrintWriter writer = response.getWriter();
            Principal userPrincipal = request.getUserPrincipal();
            if (userPrincipal != null)
            {
                @SuppressWarnings("unchecked")
                Map<String, Object> openIdInfo = (Map<String, Object>)request.getSession().getAttribute(OpenIdAuthenticator.CLAIMS);
                if (openIdInfo != null)
                {
                    writer.println("<h3>OpenID Authenticated User</h3>");
                    writer.println("userId: " + openIdInfo.get("sub") + "<br>");
                    writer.println("name: " + openIdInfo.get("name") + "<br>");
                    writer.println("email: " + openIdInfo.get("email") + "<br>");
                }
                else
                {
                    writer.println("<h3>Authenticated User</h3>");
                    writer.println("name: " + userPrincipal.getName() + "<br>");
                    writer.println("principal: " + userPrincipal.toString() + "<br>");
                }

                writer.println("<br><a href=\"/logout\">Logout</a>");
            }
            else
            {
                writer.println("not authenticated");
                writer.println("<br><a href=\"/login\">Form Login</a>");
                writer.println("<br><a href=\"/openid/login\">OpenID Login</a>");
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
