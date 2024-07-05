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

package org.eclipse.jetty.security.siwe.example;

import java.io.PrintWriter;
import java.nio.file.Paths;
import java.util.Objects;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.security.AuthenticationState;
import org.eclipse.jetty.security.Constraint;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.security.siwe.EthereumAuthenticator;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.session.SessionHandler;
import org.eclipse.jetty.util.Callback;

public class SignInWithEthereumEmbeddedExample
{
    public static void main(String[] args) throws Exception
    {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(8080);
        server.addConnector(connector);

        String resourcePath = Paths.get(Objects.requireNonNull(SignInWithEthereumEmbeddedExample.class.getClassLoader().getResource("")).toURI())
            .resolve("../../src/test/resources/")
            .normalize().toString();
        ResourceHandler resourceHandler = new ResourceHandler();
        resourceHandler.setDirAllowed(false);
        resourceHandler.setBaseResourceAsString(resourcePath);

        Handler.Abstract handler = new Handler.Wrapper(resourceHandler)
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                String pathInContext = Request.getPathInContext(request);
                if ("/login.html".equals(pathInContext))
                {
                    return super.handle(request, response, callback);
                }
                else if ("/logout".equals(pathInContext))
                {
                    AuthenticationState.logout(request, response);
                    Response.sendRedirect(request, response, callback, "/");
                    callback.succeeded();
                    return true;
                }

                AuthenticationState authState = Objects.requireNonNull(AuthenticationState.getAuthenticationState(request));
                response.getHeaders().add(HttpHeader.CONTENT_TYPE, "text/html");
                try (PrintWriter writer = new PrintWriter(Content.Sink.asOutputStream(response)))
                {
                    writer.write("UserPrincipal: " + authState.getUserPrincipal());
                    writer.write("<br><a href=\"/logout\">Logout</a>");
                }
                callback.succeeded();
                return true;
            }
        };

        EthereumAuthenticator authenticator = new EthereumAuthenticator();
        authenticator.setLoginPath("/login.html");
        SecurityHandler.PathMapped securityHandler = new SecurityHandler.PathMapped();
        securityHandler.setAuthenticator(authenticator);
        securityHandler.setHandler(handler);
        securityHandler.put("/*", Constraint.ANY_USER);

        SessionHandler sessionHandler = new SessionHandler();
        sessionHandler.setHandler(securityHandler);

        ContextHandler contextHandler = new ContextHandler();
        contextHandler.setContextPath("/");
        contextHandler.setHandler(sessionHandler);

        server.setHandler(contextHandler);
        server.start();
        System.err.println(resourceHandler.getBaseResource());
        server.join();
    }
}