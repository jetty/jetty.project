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

package org.eclipse.jetty.docs.programming.security;

import java.security.Principal;

import org.eclipse.jetty.ee.servlet.security.ConstraintMapping;
import org.eclipse.jetty.ee.servlet.security.ConstraintSecurityHandler;
import org.eclipse.jetty.ee.webapp.WebAppContext;
import org.eclipse.jetty.security.Constraint;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.resource.ResourceFactory;

import static java.lang.System.Logger.Level.INFO;

@SuppressWarnings("unused")
public class SecurityDocs
{
    public void pathMapped() throws Exception
    {
        // tag::pathMapped[]
        class AppHandler extends Handler.Abstract
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                // Retrieve the authenticated user for this request.
                Principal principal = Request.getAuthenticationState(request).getUserPrincipal();
                System.getLogger("app").log(INFO, "Current user is: {0}", principal);

                callback.succeeded();
                return true;
            }
        }

        Server server = new Server();

        // The ContextHandler for the application.
        ContextHandler contextHandler = new ContextHandler("/app");

        // HashLoginService maps users, passwords and roles
        // from the realm.properties file in the class-path.
        HashLoginService loginService = new HashLoginService();
        loginService.setConfig(ResourceFactory.of(contextHandler).newClassLoaderResource("realm.properties"));

        // Use Basic authentication, which requires a secure transport.
        BasicAuthenticator authenticator = new BasicAuthenticator();
        authenticator.setLoginService(loginService);

        // The SecurityHandler.PathMapped maps URI paths to constraints.
        SecurityHandler.PathMapped securityHandler = new SecurityHandler.PathMapped();
        securityHandler.setAuthenticator(authenticator);
        securityHandler.setLoginService(loginService);

        // Configure constraints.
        // Require that all requests use a secure transport.
        securityHandler.put("/*", Constraint.SECURE_TRANSPORT);
        // URI paths that start with /admin/ can only be accessed by users with the "admin" role.
        securityHandler.put("/admin/*", Constraint.from("admin"));

        // Link the Handlers.
        server.setHandler(contextHandler);
        contextHandler.setHandler(securityHandler);
        securityHandler.setHandler(new AppHandler());

        server.start();
        // end::pathMapped[]
    }

    public void pathMethodMapped() throws Exception
    {
        // tag::pathMethodMapped[]
        class AppHandler extends Handler.Abstract
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                // Retrieve the authenticated user for this request.
                Principal principal = Request.getAuthenticationState(request).getUserPrincipal();
                System.getLogger("app").log(INFO, "Current user is: {0}", principal);

                callback.succeeded();
                return true;
            }
        }

        Server server = new Server();

        // The ContextHandler for the application.
        ContextHandler contextHandler = new ContextHandler("/app");

        // HashLoginService maps users, passwords and roles
        // from the realm.properties file in the class-path.
        HashLoginService loginService = new HashLoginService();
        loginService.setConfig(ResourceFactory.of(contextHandler).newClassLoaderResource("realm.properties"));

        // Use Basic authentication, which requires a secure transport.
        BasicAuthenticator authenticator = new BasicAuthenticator();
        authenticator.setLoginService(loginService);

        // The SecurityHandler.PathMapped maps URI paths to constraints.
        SecurityHandler.PathMethodMapped securityHandler = new SecurityHandler.PathMethodMapped();
        securityHandler.setAuthenticator(authenticator);
        securityHandler.setLoginService(loginService);

        // Configure constraints.
        // Unless otherwise specified, access to resources is forbidden and requires secure transport.
        securityHandler.put("/*", "*", Constraint.combine(Constraint.FORBIDDEN, Constraint.SECURE_TRANSPORT));
        // GET /data/* is allowed only to users with the "read" role.
        securityHandler.put("/data/*", "GET", Constraint.from("read"));
        // PUT /data/* is allowed only to users with the "write" role.
        securityHandler.put("/data/*", "PUT", Constraint.from("write"));

        // Link the Handlers.
        server.setHandler(contextHandler);
        contextHandler.setHandler(securityHandler);
        securityHandler.setHandler(new AppHandler());

        server.start();
        // end::pathMethodMapped[]
    }

    public void jakartaPathMapped() throws Exception
    {
        // tag::jakartaPathMapped[]
        Server server = new Server();

        WebAppContext webApp = new WebAppContext();
        webApp.setContextPath("/app");
        webApp.setWar("/path/to/app.war");

        // HashLoginService maps users, passwords and roles
        // from the realm.properties file in the server class-path.
        HashLoginService loginService = new HashLoginService();
        loginService.setConfig(ResourceFactory.of(webApp).newClassLoaderResource("realm.properties"));

        // Use Basic authentication, which requires a secure transport.
        BasicAuthenticator authenticator = new BasicAuthenticator();
        authenticator.setLoginService(loginService);

        ConstraintSecurityHandler securityHandler = new ConstraintSecurityHandler();
        securityHandler.setAuthenticator(authenticator);
        securityHandler.setLoginService(loginService);

        // Configure constraints.
        ConstraintMapping constraintMapping = new ConstraintMapping();
        constraintMapping.setPathSpec("/*");
        constraintMapping.setConstraint(Constraint.SECURE_TRANSPORT);
        securityHandler.addConstraintMapping(constraintMapping);
        constraintMapping = new ConstraintMapping();
        constraintMapping.setPathSpec("/admin/*");
        constraintMapping.setConstraint(Constraint.from("admin"));
        securityHandler.addConstraintMapping(constraintMapping);

        // Link the Handlers.
        server.setHandler(webApp);
        // Note the specific call to setSecurityHandler().
        webApp.setSecurityHandler(securityHandler);

        server.start();
        // end::jakartaPathMapped[]
    }

    public void jakartaPathMethodMapped() throws Exception
    {
        // tag::jakartaPathMethodMapped[]
        Server server = new Server();

        WebAppContext webApp = new WebAppContext();
        webApp.setContextPath("/app");
        webApp.setWar("/path/to/app.war");

        // HashLoginService maps users, passwords and roles
        // from the realm.properties file in the server class-path.
        HashLoginService loginService = new HashLoginService();
        loginService.setConfig(ResourceFactory.of(webApp).newClassLoaderResource("realm.properties"));

        // Use Basic authentication, which requires a secure transport.
        BasicAuthenticator authenticator = new BasicAuthenticator();
        authenticator.setLoginService(loginService);

        ConstraintSecurityHandler securityHandler = new ConstraintSecurityHandler();
        securityHandler.setAuthenticator(authenticator);
        securityHandler.setLoginService(loginService);

        // Configure constraints.
        // Forbid access for uncovered HTTP methods.
        securityHandler.setDenyUncoveredHttpMethods(true);
        // No HTTP method specified, therefore applies to all methods.
        ConstraintMapping constraintMapping = new ConstraintMapping();
        constraintMapping.setPathSpec("/*");
        constraintMapping.setConstraint(Constraint.combine(Constraint.FORBIDDEN, Constraint.SECURE_TRANSPORT));
        securityHandler.addConstraintMapping(constraintMapping);
        // GET /data/* is allowed only to users with the "read" role.
        constraintMapping = new ConstraintMapping();
        constraintMapping.setPathSpec("/data/*");
        constraintMapping.setMethod("GET");
        constraintMapping.setConstraint(Constraint.combine(Constraint.SECURE_TRANSPORT, Constraint.from("read")));
        // PUT /data/* is allowed only to users with the "write" role.
        constraintMapping = new ConstraintMapping();
        constraintMapping.setPathSpec("/data/*");
        constraintMapping.setMethod("PUT");
        constraintMapping.setConstraint(Constraint.combine(Constraint.SECURE_TRANSPORT, Constraint.from("write")));

        // Link the Handlers.
        server.setHandler(webApp);
        // Note the specific call to setSecurityHandler().
        webApp.setSecurityHandler(securityHandler);

        server.start();
        // end::jakartaPathMethodMapped[]
    }
}
