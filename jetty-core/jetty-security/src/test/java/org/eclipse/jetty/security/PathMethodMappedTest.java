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

package org.eclipse.jetty.security;

import java.nio.file.Path;
import java.util.function.Consumer;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class PathMethodMappedTest
{
    private Server server;
    private LocalConnector connector;

    private void start(Consumer<SecurityHandler.PathMethodMapped> configurator) throws Exception
    {
        server = new Server();
        connector = new LocalConnector(server);
        server.addConnector(connector);

        SecurityHandler.PathMethodMapped securityHandler = new SecurityHandler.PathMethodMapped();
        configurator.accept(securityHandler);

        Path realm = MavenPaths.findTestResourceFile("test-realm.properties");
        HashLoginService loginService = new HashLoginService("Test", ResourceFactory.of(server).newResource(realm));
        BasicAuthenticator authenticator = new BasicAuthenticator();
        authenticator.setLoginService(loginService);
        securityHandler.setAuthenticator(authenticator);
        securityHandler.setLoginService(loginService);

        ContextHandler contextHandler = new ContextHandler(securityHandler);
        server.setHandler(contextHandler);
        server.start();
    }

    @AfterEach
    public void dispose()
    {
        LifeCycle.stop(server);
    }

    @Test
    public void testNoMappings() throws Exception
    {
        start(s ->
        {
            s.setHandler(new Handler.Abstract()
            {
                @Override
                public boolean handle(Request request, Response response, Callback callback)
                {
                    callback.succeeded();
                    return true;
                }
            });
        });

        HttpTester.Request request = HttpTester.newRequest();
        request.put(HttpHeader.AUTHORIZATION, BasicAuthenticator.authorization("test", "password"));
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request.generate()));

        // No matches, request is allowed.
        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @Test
    public void testAllPathsAllMethodsForbidden() throws Exception
    {
        start(s ->
        {
            s.put("/*", "*", Constraint.FORBIDDEN);
            s.setHandler(new Handler.Abstract()
            {
                @Override
                public boolean handle(Request request, Response response, Callback callback)
                {
                    callback.succeeded();
                    return true;
                }
            });
        });

        HttpTester.Request request = HttpTester.newRequest();
        request.put(HttpHeader.AUTHORIZATION, BasicAuthenticator.authorization("test", "password"));
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request.generate()));

        // All requests are forbidden.
        assertEquals(HttpStatus.FORBIDDEN_403, response.getStatus());
    }

    @Test
    public void testAllPathsOnlyGETAllowed() throws Exception
    {
        start(s ->
        {
            s.put("/*", "*", Constraint.FORBIDDEN);
            s.put("/*", "GET", Constraint.from("read"));
            s.setHandler(new Handler.Abstract()
            {
                @Override
                public boolean handle(Request request, Response response, Callback callback)
                {
                    Request.AuthenticationState state = Request.getAuthenticationState(request);
                    assertNotNull(state);
                    assertEquals("reader", state.getUserPrincipal().getName());
                    callback.succeeded();
                    return true;
                }
            });
        });

        // User "test" does not have roles, so forbidden.
        HttpTester.Request request = HttpTester.newRequest();
        request.put(HttpHeader.AUTHORIZATION, BasicAuthenticator.authorization("test", "password"));
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request.generate()));
        assertEquals(HttpStatus.FORBIDDEN_403, response.getStatus());

        // User "reader" has role "read", so it can only perform GET requests.
        request = HttpTester.newRequest();
        request.put(HttpHeader.AUTHORIZATION, BasicAuthenticator.authorization("reader", "password"));
        response = HttpTester.parseResponse(connector.getResponse(request.generate()));
        assertEquals(HttpStatus.OK_200, response.getStatus());

        request = HttpTester.newRequest();
        request.setMethod("PUT");
        request.put(HttpHeader.CONTENT_LENGTH, 0);
        request.put(HttpHeader.AUTHORIZATION, BasicAuthenticator.authorization("reader", "password"));
        response = HttpTester.parseResponse(connector.getResponse(request.generate()));
        assertEquals(HttpStatus.FORBIDDEN_403, response.getStatus());
    }

    @Test
    public void testAllPathsOnlyPUTAllowed() throws Exception
    {
        start(s ->
        {
            s.put("/*", "*", Constraint.FORBIDDEN);
            s.put("/*", "PUT", Constraint.from("write"));
            s.setHandler(new Handler.Abstract()
            {
                @Override
                public boolean handle(Request request, Response response, Callback callback)
                {
                    Request.AuthenticationState state = Request.getAuthenticationState(request);
                    assertNotNull(state);
                    assertEquals("writer", state.getUserPrincipal().getName());
                    callback.succeeded();
                    return true;
                }
            });
        });

        // User "test" does not have roles, so forbidden.
        HttpTester.Request request = HttpTester.newRequest();
        request.put(HttpHeader.AUTHORIZATION, BasicAuthenticator.authorization("test", "password"));
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request.generate()));
        assertEquals(HttpStatus.FORBIDDEN_403, response.getStatus());

        // User "reader" has role "read", so it can only perform GET requests.
        request = HttpTester.newRequest();
        request.put(HttpHeader.AUTHORIZATION, BasicAuthenticator.authorization("reader", "password"));
        response = HttpTester.parseResponse(connector.getResponse(request.generate()));
        assertEquals(HttpStatus.FORBIDDEN_403, response.getStatus());

        request = HttpTester.newRequest();
        request.setMethod("PUT");
        request.put(HttpHeader.CONTENT_LENGTH, 0);
        request.put(HttpHeader.AUTHORIZATION, BasicAuthenticator.authorization("reader", "password"));
        response = HttpTester.parseResponse(connector.getResponse(request.generate()));
        assertEquals(HttpStatus.FORBIDDEN_403, response.getStatus());

        // User "writer" has role "write", so it can only perform PUT requests.
        request = HttpTester.newRequest();
        request.put(HttpHeader.AUTHORIZATION, BasicAuthenticator.authorization("writer", "password"));
        response = HttpTester.parseResponse(connector.getResponse(request.generate()));
        assertEquals(HttpStatus.FORBIDDEN_403, response.getStatus());

        request = HttpTester.newRequest();
        request.setMethod("PUT");
        request.put(HttpHeader.CONTENT_LENGTH, 0);
        request.put(HttpHeader.AUTHORIZATION, BasicAuthenticator.authorization("writer", "password"));
        response = HttpTester.parseResponse(connector.getResponse(request.generate()));
        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @Test
    public void allPathsGETAndPUTAllowed() throws Exception
    {
        start(s ->
        {
            s.put("/*", "*", Constraint.FORBIDDEN);
            s.put("/*", "GET", Constraint.from("read"));
            s.put("/*", "PUT", Constraint.from("write"));
            s.setHandler(new Handler.Abstract()
            {
                @Override
                public boolean handle(Request request, Response response, Callback callback)
                {
                    Request.AuthenticationState state = Request.getAuthenticationState(request);
                    assertNotNull(state);
                    assertEquals("admin", state.getUserPrincipal().getName());
                    callback.succeeded();
                    return true;
                }
            });
        });

        // User "test" does not have roles, so forbidden.
        HttpTester.Request request = HttpTester.newRequest();
        request.put(HttpHeader.AUTHORIZATION, BasicAuthenticator.authorization("test", "password"));
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request.generate()));
        assertEquals(HttpStatus.FORBIDDEN_403, response.getStatus());

        // User "admin" has both read and write roles.
        request = HttpTester.newRequest();
        request.put(HttpHeader.AUTHORIZATION, BasicAuthenticator.authorization("admin", "password"));
        response = HttpTester.parseResponse(connector.getResponse(request.generate()));
        assertEquals(HttpStatus.OK_200, response.getStatus());

        request = HttpTester.newRequest();
        request.setMethod("PUT");
        request.put(HttpHeader.CONTENT_LENGTH, 0);
        request.put(HttpHeader.AUTHORIZATION, BasicAuthenticator.authorization("admin", "password"));
        response = HttpTester.parseResponse(connector.getResponse(request.generate()));
        assertEquals(HttpStatus.OK_200, response.getStatus());
    }
}
