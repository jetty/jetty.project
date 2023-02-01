//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server.handler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class SecuredRedirectHandlerTest
{
    private static Server server;
    private static URI serverHttpUri;
    private static URI serverHttpsUri;

    public void startServer(Handler handler) throws Exception
    {
        // Setup SSL
        Path keystore = MavenPaths.findTestResourceFile("keystore.p12");
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath(keystore.toUri().toASCIIString());
        sslContextFactory.setKeyStorePassword("storepwd");

        server = new Server();

        // Setup HTTP Configuration
        HttpConfiguration httpConf = new HttpConfiguration();
        httpConf.setSecureScheme("https");

        ServerConnector httpConnector = new ServerConnector(server, new HttpConnectionFactory(httpConf));
        httpConnector.setName("unsecured");
        httpConnector.setPort(0);

        // Setup HTTPS Configuration
        HttpConfiguration httpsConf = new HttpConfiguration(httpConf);
        httpsConf.addCustomizer(new SecureRequestCustomizer());

        ServerConnector httpsConnector = new ServerConnector(server, new SslConnectionFactory(sslContextFactory, "http/1.1"), new HttpConnectionFactory(httpsConf));
        httpsConnector.setName("secured");
        httpsConnector.setPort(0);

        // Add connectors
        server.addConnector(httpConnector);
        server.addConnector(httpsConnector);

        // Create server level handler tree
        server.setHandler(handler);

        server.start();

        // calculate Server URIs
        String host = httpConnector.getHost();
        if (host == null)
        {
            host = "localhost";
        }
        serverHttpUri = new URI(String.format("http://localhost:%d/", httpConnector.getLocalPort()));
        serverHttpsUri = new URI(String.format("https://localhost:%d/", httpsConnector.getLocalPort()));
        httpConf.setSecurePort(httpsConnector.getLocalPort());
    }

    @AfterAll
    public static void stopServer() throws Exception
    {
        LifeCycle.stop(server);
    }

    /**
     * Access the root resource in an unsecured way that is protected by the SecuredRedirectHandler.
     * Should result in a redirect to the secure location.
     */
    @Test
    public void testRedirectUnsecuredRoot() throws Exception
    {
        ContextHandler test1Context = new ContextHandler();
        test1Context.setContextPath("/test1");
        test1Context.setHandler(new HelloHandler("Hello-from-test1"));

        ContextHandler test2Context = new ContextHandler();
        test2Context.setContextPath("/test2");
        test2Context.setHandler(new HelloHandler("Hello-from-test2"));

        ContextHandlerCollection contextHandlerCollection = new ContextHandlerCollection();
        contextHandlerCollection.addHandler(test1Context);
        contextHandlerCollection.addHandler(test2Context);

        SecuredRedirectHandler securedRedirectHandler = new SecuredRedirectHandler();
        securedRedirectHandler.setHandler(contextHandlerCollection);

        startServer(securedRedirectHandler);

        URI destURI = serverHttpUri.resolve("/");
        try (Socket socket = newSocket(destURI);
             OutputStream output = socket.getOutputStream();
             InputStream input = socket.getInputStream())
        {
            String rawRequest = """
                GET %s HTTP/1.1
                Host: %s:%d
                Connection: close
                
                """.formatted(destURI.getRawPath(), destURI.getHost(), destURI.getPort());
            output.write(rawRequest.getBytes(StandardCharsets.UTF_8));
            output.flush();
            HttpTester.Response response = HttpTester.parseResponse(input);
            assertEquals(HttpStatus.MOVED_TEMPORARILY_302, response.getStatus());
            URI expectedURI = serverHttpsUri.resolve("/");
            assertEquals(expectedURI.toASCIIString(), response.get(HttpHeader.LOCATION));
        }
    }

    /**
     * Access a resource in a secured way that is protected by the SecuredRedirectHandler.
     * Normal access to the resource should occur.
     */
    @Test
    public void testSecuredRequestToProtectedHandler() throws Exception
    {
        ContextHandler test1Context = new ContextHandler();
        test1Context.setContextPath("/test1");
        test1Context.setHandler(new HelloHandler("Hello-from-test1"));

        SecuredRedirectHandler securedRedirectHandler = new SecuredRedirectHandler();
        securedRedirectHandler.setHandler(test1Context);

        startServer(securedRedirectHandler);

        URI destURI = serverHttpsUri.resolve("/test1/info");
        try (Socket socket = newSecureSocket(destURI);
             OutputStream output = socket.getOutputStream();
             InputStream input = socket.getInputStream())
        {
            String rawRequest = """
                GET %s HTTP/1.1
                Host: %s:%d
                Connection: close
                
                """.formatted(destURI.getRawPath(), destURI.getHost(), destURI.getPort());
            output.write(rawRequest.getBytes(StandardCharsets.UTF_8));
            output.flush();
            HttpTester.Response response = HttpTester.parseResponse(input);
            assertEquals(HttpStatus.OK_200, response.getStatus());
            assertThat(response.getContent(), is("Hello-from-test1"));
        }
    }

    /**
     * Access a resource in an unsecured way that is protected by the SecuredRedirectHandler.
     * Should result in a redirect to the secure location.
     */
    @Test
    public void testUnsecuredRequestToProtectedHandler() throws Exception
    {
        ContextHandler test1Context = new ContextHandler();
        test1Context.setContextPath("/test1");
        test1Context.setHandler(new HelloHandler("Hello-from-test1"));

        SecuredRedirectHandler securedRedirectHandler = new SecuredRedirectHandler();
        securedRedirectHandler.setHandler(test1Context);

        startServer(securedRedirectHandler);

        URI destURI = serverHttpUri.resolve("/test1/info");
        try (Socket socket = newSocket(destURI);
             OutputStream output = socket.getOutputStream();
             InputStream input = socket.getInputStream())
        {
            String rawRequest = """
                GET %s HTTP/1.1
                Host: %s:%d
                Connection: close
                
                """.formatted(destURI.getRawPath(), destURI.getHost(), destURI.getPort());
            output.write(rawRequest.getBytes(StandardCharsets.UTF_8));
            output.flush();
            HttpTester.Response response = HttpTester.parseResponse(input);
            assertEquals(HttpStatus.MOVED_TEMPORARILY_302, response.getStatus());
            URI expectedURI = serverHttpsUri.resolve("/test1/info");
            assertEquals(expectedURI.toASCIIString(), response.get(HttpHeader.LOCATION));
        }
    }

    /**
     * Attempt to access a resource that doesn't exist in unsecure mode.
     * This should redirect to the same non-existent resource in secure mode.
     */
    @Test
    public void testUnsecuredRequestTo404() throws Exception
    {
        ContextHandler test1Context = new ContextHandler();
        test1Context.setContextPath("/test1");
        test1Context.setHandler(new HelloHandler("Hello1"));

        SecuredRedirectHandler securedRedirectHandler = new SecuredRedirectHandler();
        securedRedirectHandler.setHandler(test1Context);

        startServer(securedRedirectHandler);

        URI destURI = serverHttpUri.resolve("/nothing/here");
        try (Socket socket = newSocket(destURI);
             OutputStream output = socket.getOutputStream();
             InputStream input = socket.getInputStream())
        {
            String rawRequest = """
                GET %s HTTP/1.1
                Host: %s:%d
                Connection: close
                
                """.formatted(destURI.getRawPath(), destURI.getHost(), destURI.getPort());
            output.write(rawRequest.getBytes(StandardCharsets.UTF_8));
            output.flush();
            HttpTester.Response response = HttpTester.parseResponse(input);
            assertEquals(HttpStatus.MOVED_TEMPORARILY_302, response.getStatus());
            URI expectedURI = serverHttpsUri.resolve("/nothing/here");
            assertEquals(expectedURI.toASCIIString(), response.get(HttpHeader.LOCATION));
        }
    }

    /**
     * Secure request to non-existent resource
     */
    @Test
    public void testSecuredRequestTo404() throws Exception
    {
        ContextHandler test1Context = new ContextHandler();
        test1Context.setContextPath("/test1");
        test1Context.setHandler(new HelloHandler("Hello1"));

        SecuredRedirectHandler securedRedirectHandler = new SecuredRedirectHandler();
        securedRedirectHandler.setHandler(test1Context);

        startServer(securedRedirectHandler);

        URI destURI = serverHttpsUri.resolve("/nothing/here");
        try (Socket socket = newSecureSocket(destURI);
             OutputStream output = socket.getOutputStream();
             InputStream input = socket.getInputStream())
        {
            String rawRequest = """
                GET %s HTTP/1.1
                Host: %s:%d
                Connection: close
                
                """.formatted(destURI.getRawPath(), destURI.getHost(), destURI.getPort());
            output.write(rawRequest.getBytes(StandardCharsets.UTF_8));
            output.flush();
            HttpTester.Response response = HttpTester.parseResponse(input);
            assertEquals(HttpStatus.NOT_FOUND_404, response.getStatus());
        }
    }

    /**
     * An unsecured request to a resource that passes through the {@link SecuredRedirectHandler}
     * but the underlying Handler doesn't process the request (returns false), the redirect
     * will occur before the child handler is processed.
     */
    @Test
    public void testUnsecuredRequestToNullChildHandler() throws Exception
    {
        Handler.Collection handlers = new Handler.Collection();
        SecuredRedirectHandler securedRedirectHandler = new SecuredRedirectHandler(HttpStatus.MOVED_PERMANENTLY_301);
        handlers.addHandler(securedRedirectHandler); // first handler (no children)
        handlers.addHandler(new HelloHandler("Hello-from-test"));

        startServer(handlers);

        URI destURI = serverHttpUri.resolve("/foo");
        try (Socket socket = newSocket(destURI);
             OutputStream output = socket.getOutputStream();
             InputStream input = socket.getInputStream())
        {
            String rawRequest = """
                GET %s HTTP/1.1
                Host: %s:%d
                Connection: close
                
                """.formatted(destURI.getRawPath(), destURI.getHost(), destURI.getPort());
            output.write(rawRequest.getBytes(StandardCharsets.UTF_8));
            output.flush();
            HttpTester.Response response = HttpTester.parseResponse(input);
            assertEquals(HttpStatus.MOVED_PERMANENTLY_301, response.getStatus());
            URI expectedURI = serverHttpsUri.resolve("/foo");
            assertEquals(expectedURI.toASCIIString(), response.get(HttpHeader.LOCATION));
        }

        destURI = serverHttpsUri.resolve("/foo");
        try (Socket socket = newSecureSocket(destURI);
             OutputStream output = socket.getOutputStream();
             InputStream input = socket.getInputStream())
        {
            String rawRequest = """
                GET %s HTTP/1.1
                Host: %s:%d
                Connection: close
                
                """.formatted(destURI.getRawPath(), destURI.getHost(), destURI.getPort());
            output.write(rawRequest.getBytes(StandardCharsets.UTF_8));
            output.flush();
            HttpTester.Response response = HttpTester.parseResponse(input);
            assertEquals(HttpStatus.OK_200, response.getStatus());
            assertThat(response.getContent(), is("Hello-from-test"));
        }
    }

    private Socket newSocket(URI destURI) throws NoSuchAlgorithmException, KeyManagementException, IOException
    {
        return new Socket(destURI.getHost(), destURI.getPort());
    }

    private Socket newSecureSocket(URI destURI) throws NoSuchAlgorithmException, KeyManagementException, IOException
    {
        SSLContext ctx = SSLContext.getInstance("TLSv1.2");
        ctx.init(null, SslContextFactory.TRUST_ALL_CERTS, null);
        SSLSocketFactory socketFactory = ctx.getSocketFactory();
        return socketFactory.createSocket(destURI.getHost(), destURI.getPort());
    }
}
