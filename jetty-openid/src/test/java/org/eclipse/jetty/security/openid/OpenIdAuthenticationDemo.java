//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.security.Authenticator;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.security.Constraint;

public class OpenIdAuthenticationDemo
{
    public static class AdminPage extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException
        {
            response.getWriter().println("<p>this is the admin page "+request.getUserPrincipal()+": <a href=\"/\">Home</a></p>");
        }
    }

    public static class LoginPage extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException
        {
            response.getWriter().println("<p>you logged in  <a href=\"/\">Home</a></p>");
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

    public static class HomePage extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException
        {
            response.setContentType(MimeTypes.Type.TEXT_HTML.asString());
            response.getWriter().println("<h1>Home Page</h1>");

            Principal userPrincipal = request.getUserPrincipal();
            if (userPrincipal != null)
            {
                Map<String, Object> userInfo = (Map)request.getSession().getAttribute(OpenIdAuthenticator.__USER_CLAIMS);
                response.getWriter().println("<p>Welcome: " + userInfo.get("name") + "</p>");
                response.getWriter().println("<a href=\"/profile\">Profile</a><br>");
                response.getWriter().println("<a href=\"/admin\">Admin</a><br>");
                response.getWriter().println("<a href=\"/logout\">Logout</a><br>");
            }
            else
            {
                response.getWriter().println("<p>Please Login  <a href=\"/login\">Login</a></p>");
            }
        }
    }

    public static class ProfilePage extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException
        {
            response.setContentType(MimeTypes.Type.TEXT_HTML.asString());
            Map<String, Object> userInfo = (Map)request.getSession().getAttribute(OpenIdAuthenticator.__USER_CLAIMS);

            response.getWriter().println("<!-- Add icon library -->\n" +
                "<div class=\"card\">\n" +
                "  <img src=\""+userInfo.get("picture")+"\" style=\"width:30%\">\n" +
                "  <h1>"+ userInfo.get("name") +"</h1>\n" +
                "  <p class=\"title\">"+userInfo.get("email")+"</p>\n" +
                "  <p>UserId: " + userInfo.get("sub") +"</p>\n" +
                "</div>");

            response.getWriter().println("<a href=\"/\">Home</a><br>");
            response.getWriter().println("<a href=\"/logout\">Logout</a><br>");
        }
    }

    public static class ErrorPage extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException
        {
            response.setContentType(MimeTypes.Type.TEXT_HTML.asString());
            response.getWriter().println("<h1>error: not authorized</h1>");
            response.getWriter().println("<p>" + request.getUserPrincipal() + "</p>");
        }
    }

    public static void main(String[] args) throws Exception
    {
        Server server = new Server(8080);
        ServletContextHandler context = new ServletContextHandler(server, "/", ServletContextHandler.SESSIONS);

        // Add servlets
        context.addServlet(ProfilePage.class, "/profile");
        context.addServlet(LoginPage.class, "/login");
        context.addServlet(AdminPage.class, "/admin");
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


        // Google Authentication
        OpenIdConfiguration configuration = new OpenIdConfiguration(
            "https://accounts.google.com/",
            "1051168419525-5nl60mkugb77p9j194mrh287p1e0ahfi.apps.googleusercontent.com",
            "XT_MIsSv_aUCGollauCaJY8S");
        configuration.addScopes("email", "profile");

        /*
        // Microsoft Authentication
        OpenIdConfiguration configuration = new OpenIdConfiguration(
            "https://login.microsoftonline.com/common/v2.0",
            "5f05dea8-2bd9-45de-b30f-cf5c102b8784",
            "IfhQJKi-5[vxhh_=ldqt0y4PkV3z_1ca");
        */

        /*
        // Yahoo Authentication
        OpenIdConfiguration configuration = new OpenIdConfiguration(
            "https://login.yahoo.com",
            "dj0yJmk9ME5Id05yTkdGNDdPJmQ9WVdrOU9VcHVZWEp4TkdrbWNHbzlNQS0tJnM9Y29uc3VtZXJzZWNyZXQmc3Y9MCZ4PTE2",
            "1e7f0eeb0ba0af9d9198f9be760f66ae3ea9e3b5");
        configuration.addScopes("sdps-r");
        */

        /*
        // Create a realm.properties file to associate roles with users
        Path tmpDir = Paths.get(System.getProperty("java.io.tmpdir"));
        Path tmpPath = Files.createTempFile(tmpDir, "realm", ".properties");
        tmpPath.toFile().deleteOnExit();
        try (BufferedWriter writer = Files.newBufferedWriter(tmpPath, StandardCharsets.UTF_8, StandardOpenOption.WRITE))
        {
            // <userId>:[,<rolename> ...]
            writer.write("114260987481616800581:,admin");
        }

        // This must be added to the OpenIdLoginService in constructor below
        HashLoginService hashLoginService = new HashLoginService();
        hashLoginService.setConfig(tmpPath.toAbsolutePath().toString());
        hashLoginService.setHotReload(true);
         */

        // Configure OpenIdLoginService optionally providing a base LoginService to provide user roles
        OpenIdLoginService loginService = new OpenIdLoginService(configuration);//, hashLoginService);
        securityHandler.setLoginService(loginService);

        Authenticator authenticator = new OpenIdAuthenticator(configuration, "/error");
        securityHandler.setAuthenticator(authenticator);
        context.setSecurityHandler(securityHandler);

        server.start();
        server.join();
    }
}
