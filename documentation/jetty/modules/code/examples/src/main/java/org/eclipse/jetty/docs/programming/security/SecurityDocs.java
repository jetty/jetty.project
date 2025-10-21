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

import org.eclipse.jetty.security.Constraint;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
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
        // Require that all requests use a secure transport.
        securityHandler.put("/*", Constraint.SECURE_TRANSPORT);
        // URI paths that start with /admin/ can only be accessed by users with the "admin" role.
        securityHandler.put("/admin/*", Constraint.from("admin"));
        securityHandler.setAuthenticator(authenticator);
        securityHandler.setLoginService(loginService);

        server.setHandler(contextHandler);
        contextHandler.setHandler(securityHandler);
        securityHandler.setHandler(new Handler.Abstract()
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
        });

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

        ServerConnector connector = new ServerConnector(server);
        connector.setPort(37023);
        server.addConnector(connector);

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
        // Unless otherwise specified, access to resources is forbidden and requires secure transport.
        securityHandler.put("/*", "*", Constraint.combine(Constraint.FORBIDDEN, Constraint.SECURE_TRANSPORT));
        // GET /data/* is allowed only to users with the "read" role.
        securityHandler.put("/data/*", "GET", Constraint.from("read"));
        // PUT /data/* is allowed only to users with the "write" role.
        securityHandler.put("/data/*", "PUT", Constraint.from("write"));
        securityHandler.setAuthenticator(authenticator);
        securityHandler.setLoginService(loginService);

        server.setHandler(contextHandler);
        contextHandler.setHandler(securityHandler);
        securityHandler.setHandler(new AppHandler());

        server.start();
        // end::pathMethodMapped[]
    }

    public static void main(String[] args) throws Exception
    {
        new SecurityDocs().pathMethodMapped();
    }
}
