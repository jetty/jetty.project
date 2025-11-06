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

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.function.Consumer;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PathMethodMappedTest
{
    private Server server;
    private ServerConnector connector;
    private ServerConnector tlsConnector;

    private void start(Consumer<SecurityHandler.PathMethodMapped> configurator) throws Exception
    {
        server = new Server();
        HttpConfiguration httpConfig = new HttpConfiguration();
        connector = new ServerConnector(server, 1, 1, new HttpConnectionFactory(httpConfig));
        server.addConnector(connector);
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath(MavenPaths.findTestResourceFile("keystore.p12"));
        sslContextFactory.setKeyStorePassword("storepwd");
        tlsConnector = new ServerConnector(server, 1, 1, sslContextFactory);
        server.addConnector(tlsConnector);

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

        httpConfig.setSecurePort(tlsConnector.getLocalPort());
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

        try (SocketChannel client = SocketChannel.open(new InetSocketAddress("localhost", connector.getLocalPort())))
        {
            client.write(UTF_8.encode("""
                GET / HTTP/1.1
                Host: localhost
                Authorization: %s
                
                """.formatted(BasicAuthenticator.authorization("test", "password")))
            );

            HttpTester.Response response = HttpTester.parseResponse(client);
            // No path matches, request is allowed.
            assertEquals(HttpStatus.OK_200, response.getStatus());
        }
    }

    @Test
    public void testAllPathsOneMethodMappingRequestWithOtherMethodAllowed() throws Exception
    {
        start(s ->
        {
            s.put("/*", "TRACE", Constraint.FORBIDDEN);
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

        try (SocketChannel client = SocketChannel.open(new InetSocketAddress("localhost", connector.getLocalPort())))
        {
            client.write(UTF_8.encode("""
                GET / HTTP/1.1
                Host: localhost
                Authorization: %s
                
                """.formatted(BasicAuthenticator.authorization("test", "password")))
            );

            HttpTester.Response response = HttpTester.parseResponse(client);
            // No method matches, request is allowed.
            assertEquals(HttpStatus.OK_200, response.getStatus());
        }
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

        try (SocketChannel client = SocketChannel.open(new InetSocketAddress("localhost", connector.getLocalPort())))
        {
            client.write(UTF_8.encode("""
                GET / HTTP/1.1
                Host: localhost
                Authorization: %s
                
                """.formatted(BasicAuthenticator.authorization("test", "password")))
            );

            HttpTester.Response response = HttpTester.parseResponse(client);
            // All requests are forbidden.
            assertEquals(HttpStatus.FORBIDDEN_403, response.getStatus());
        }
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

        try (SocketChannel client = SocketChannel.open(new InetSocketAddress("localhost", connector.getLocalPort())))
        {
            client.write(UTF_8.encode("""
                GET / HTTP/1.1
                Host: localhost
                Authorization: %s
                
                """.formatted(BasicAuthenticator.authorization("test", "password")))
            );

            HttpTester.Response response = HttpTester.parseResponse(client);
            // User "test" does not have roles, so forbidden.
            assertEquals(HttpStatus.FORBIDDEN_403, response.getStatus());

            client.write(UTF_8.encode("""
                GET / HTTP/1.1
                Host: localhost
                Authorization: %s
                
                """.formatted(BasicAuthenticator.authorization("reader", "password")))
            );

            response = HttpTester.parseResponse(client);
            // User "reader" has role "read", so it can only perform GET requests.
            assertEquals(HttpStatus.OK_200, response.getStatus());

            client.write(UTF_8.encode("""
                PUT /file.txt HTTP/1.1
                Host: localhost
                Content-Length: 0
                Authorization: %s
                
                """.formatted(BasicAuthenticator.authorization("reader", "password")))
            );

            response = HttpTester.parseResponse(client);
            // Method PUT is forbidden.
            assertEquals(HttpStatus.FORBIDDEN_403, response.getStatus());
        }
    }

    @Test
    public void testAllPathsOnlyGETAllowedSecureTransport() throws Exception
    {
        start(s ->
        {
            s.put("/*", "*", Constraint.combine(Constraint.FORBIDDEN, Constraint.SECURE_TRANSPORT));
            s.put("/*", "GET", Constraint.from("read"));
            s.setHandler(new Handler.Abstract()
            {
                @Override
                public boolean handle(Request request, Response response, Callback callback)
                {
                    assertTrue(request.isSecure());
                    Request.AuthenticationState state = Request.getAuthenticationState(request);
                    assertNotNull(state);
                    assertEquals("reader", state.getUserPrincipal().getName());
                    callback.succeeded();
                    return true;
                }
            });
        });

        try (SocketChannel client = SocketChannel.open(new InetSocketAddress("localhost", connector.getLocalPort())))
        {
            client.write(UTF_8.encode("""
                GET / HTTP/1.1
                Host: localhost
                Authorization: %s
                
                """.formatted(BasicAuthenticator.authorization("test", "password")))
            );

            HttpTester.Response response = HttpTester.parseResponse(client);
            // Clear text, redirect to secure.
            assertTrue(HttpStatus.isRedirection(response.getStatus()));
            String location = response.get(HttpHeader.LOCATION);
            assertNotNull(location);
            assertThat(location, startsWith("https://"));

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, SslContextFactory.TRUST_ALL_CERTS, null);
            SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();
            try (Socket secureClient = sslSocketFactory.createSocket("localhost", tlsConnector.getLocalPort()))
            {
                String request = """
                    GET / HTTP/1.1
                    Host: localhost
                    Authorization: %s
                    
                    """.formatted(BasicAuthenticator.authorization("test", "password"));
                OutputStream output = secureClient.getOutputStream();
                output.write(request.getBytes(UTF_8));
                output.flush();

                InputStream input = secureClient.getInputStream();
                response = HttpTester.parseResponse(input);
                // Unauthorized user.
                assertEquals(HttpStatus.FORBIDDEN_403, response.getStatus());

                request = """
                    GET / HTTP/1.1
                    Host: localhost
                    Authorization: %s
                    
                    """.formatted(BasicAuthenticator.authorization("reader", "password"));
                output.write(request.getBytes(UTF_8));
                output.flush();

                response = HttpTester.parseResponse(input);
                // Authorized user.
                assertEquals(HttpStatus.OK_200, response.getStatus());
            }
        }
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

        try (SocketChannel client = SocketChannel.open(new InetSocketAddress("localhost", connector.getLocalPort())))
        {
            client.write(UTF_8.encode("""
                GET / HTTP/1.1
                Host: localhost
                Authorization: %s
                
                """.formatted(BasicAuthenticator.authorization("test", "password")))
            );

            HttpTester.Response response = HttpTester.parseResponse(client);
            // GET not allowed.
            assertEquals(HttpStatus.FORBIDDEN_403, response.getStatus());

            client.write(UTF_8.encode("""
                PUT / HTTP/1.1
                Host: localhost
                Content-Length: 0
                Authorization: %s
                
                """.formatted(BasicAuthenticator.authorization("reader", "password")))
            );

            response = HttpTester.parseResponse(client);
            // PUT from user with wrong role, forbidden.
            assertEquals(HttpStatus.FORBIDDEN_403, response.getStatus());

            client.write(UTF_8.encode("""
                PUT / HTTP/1.1
                Host: localhost
                Content-Length: 0
                Authorization: %s
                
                """.formatted(BasicAuthenticator.authorization("writer", "password")))
            );

            response = HttpTester.parseResponse(client);
            // PUT from user with right role, allowed.
            assertEquals(HttpStatus.OK_200, response.getStatus());

            client.write(UTF_8.encode("""
                GET / HTTP/1.1
                Host: localhost
                Authorization: %s
                
                """.formatted(BasicAuthenticator.authorization("writer", "password")))
            );

            response = HttpTester.parseResponse(client);
            // GET from writer user, not allowed.
            assertEquals(HttpStatus.FORBIDDEN_403, response.getStatus());
        }
    }

    @Test
    public void testAllPathsGETAndPUTAllowed() throws Exception
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

        try (SocketChannel client = SocketChannel.open(new InetSocketAddress("localhost", connector.getLocalPort())))
        {
            client.write(UTF_8.encode("""
                GET / HTTP/1.1
                Host: localhost
                Authorization: %s
                
                """.formatted(BasicAuthenticator.authorization("admin", "password")))
            );

            HttpTester.Response response = HttpTester.parseResponse(client);
            // User "admin" has both read and write roles, allowed.
            assertEquals(HttpStatus.OK_200, response.getStatus());

            client.write(UTF_8.encode("""
                PUT / HTTP/1.1
                Host: localhost
                Content-Length: 0
                Authorization: %s
                
                """.formatted(BasicAuthenticator.authorization("admin", "password")))
            );

            response = HttpTester.parseResponse(client);
            // User "admin" has both read and write roles, allowed.
            assertEquals(HttpStatus.OK_200, response.getStatus());
        }
    }
}
