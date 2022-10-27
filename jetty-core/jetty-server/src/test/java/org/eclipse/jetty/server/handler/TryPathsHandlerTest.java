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

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import javax.net.ssl.SSLSocket;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.pathmap.ServletPathSpec;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TryPathsHandlerTest
{
    public WorkDir workDir;
    private Server server;
    private SslContextFactory.Server sslContextFactory;
    private ServerConnector connector;
    private ServerConnector sslConnector;
    private Path rootPath;
    private String contextPath;

    private void start(List<String> paths, Handler handler) throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server, 1, 1);
        server.addConnector(connector);

        sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath("src/test/resources/keystore.p12");
        sslContextFactory.setKeyStorePassword("storepwd");
        sslConnector = new ServerConnector(server, 1, 1, sslContextFactory);
        server.addConnector(sslConnector);

        contextPath = "/ctx";
        ContextHandler context = new ContextHandler(contextPath);
        rootPath = workDir.getEmptyPathDir();
        context.setBaseResourceAsPath(rootPath);
        server.setHandler(context);

        TryPathsHandler tryPaths = new TryPathsHandler();
        context.setHandler(tryPaths);

        tryPaths.setPaths(paths);
        tryPaths.setHandler(handler);

        server.start();
    }

    @AfterEach
    public void dispose()
    {
        LifeCycle.stop(server);
    }

    @Test
    public void testTryPaths() throws Exception
    {
        ResourceHandler resourceHandler = new ResourceHandler();
        resourceHandler.setHandler(new NoContentHandler());

        start(List.of("/maintenance.txt", "$path", "/forward?p=$path"), resourceHandler);

        try (SocketChannel channel = SocketChannel.open())
        {
            channel.connect(new InetSocketAddress("localhost", connector.getLocalPort()));

            // Make a first request without existing file paths.
            HttpTester.Request request = HttpTester.newRequest();
            request.setURI(contextPath + "/last");
            channel.write(request.generate());
            HttpTester.Response response = HttpTester.parseResponse(channel);
            assertNotNull(response);
            assertEquals(HttpStatus.NO_CONTENT_204, response.getStatus());

            // Create the specific file that is requested.
            String path = "idx.txt";
            Files.writeString(rootPath.resolve(path), "hello", StandardOpenOption.CREATE);
            // Make a second request with the specific file.
            request = HttpTester.newRequest();
            request.setURI(contextPath + "/" + path);
            channel.write(request.generate());
            response = HttpTester.parseResponse(channel);
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());
            assertEquals("hello", response.getContent());

            // Create the "maintenance" file, it should be served first.
            path = "maintenance.txt";
            Files.writeString(rootPath.resolve(path), "maintenance", StandardOpenOption.CREATE);
            // Make a second request with any path, we should get the maintenance file.
            request = HttpTester.newRequest();
            request.setURI(contextPath + "/whatever");
            channel.write(request.generate());
            response = HttpTester.parseResponse(channel);
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());
            assertEquals("maintenance", response.getContent());
        }
    }

    @Test
    public void testTryPathsPhpPathMappingsHandler() throws Exception
    {
        ResourceHandler resourceHandler = new ResourceHandler();

        PathMappingsHandler pathMappingsHandler = new PathMappingsHandler();
        pathMappingsHandler.addMapping(new ServletPathSpec("/"), resourceHandler);
        pathMappingsHandler.addMapping(new ServletPathSpec("*.php"), new ExamplePhpHandler());
        pathMappingsHandler.addMapping(new ServletPathSpec("/forward"), new NoContentHandler());

        start(List.of("/maintenance.txt", "$path", "/forward?p=$path"), pathMappingsHandler);

        try (SocketChannel channel = SocketChannel.open())
        {
            channel.connect(new InetSocketAddress("localhost", connector.getLocalPort()));

            // Request something that doesn't exist
            HttpTester.Request request = HttpTester.newRequest();
            request.setURI(contextPath + "/last");
            channel.write(request.generate());
            HttpTester.Response response = HttpTester.parseResponse(channel);
            assertNotNull(response);
            assertEquals(HttpStatus.NO_CONTENT_204, response.getStatus());

            // Create the specific static file that is requested.
            String path = "idx.txt";
            Files.writeString(rootPath.resolve(path), "hello", StandardOpenOption.CREATE);
            // Make a second request with the specific file.
            request = HttpTester.newRequest();
            request.setURI(contextPath + "/" + path);
            channel.write(request.generate());
            response = HttpTester.parseResponse(channel);
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());
            assertEquals("hello", response.getContent());

            // Request a php resource
            Files.writeString(rootPath.resolve("index.php"), "raw-php-contents", StandardOpenOption.CREATE);
            request = HttpTester.newRequest();
            request.setURI(contextPath + "/index.php");
            channel.write(request.generate());
            response = HttpTester.parseResponse(channel);
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());
            assertThat(response.getContent(), startsWith("Example PHP: pathInContext=/index.php"));

            // Create the "maintenance" file, it should be served first.
            path = "maintenance.txt";
            Files.writeString(rootPath.resolve(path), "maintenance", StandardOpenOption.CREATE);
            // Make a second request with any path, we should get the maintenance file.
            request = HttpTester.newRequest();
            request.setURI(contextPath + "/whatever");
            channel.write(request.generate());
            response = HttpTester.parseResponse(channel);
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());
            assertEquals("maintenance", response.getContent());
        }
    }

    @Test
    public void testSecureRequestIsForwarded() throws Exception
    {
        String path = "/secure";
        start(List.of("$path"), new Handler.Processor()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
                HttpURI httpURI = request.getHttpURI();
                assertEquals("https", httpURI.getScheme());
                assertTrue(request.isSecure());
                assertEquals(path, Request.getPathInContext(request));
                callback.succeeded();
            }
        });

        try (SSLSocket sslSocket = sslContextFactory.newSslSocket())
        {
            sslSocket.connect(new InetSocketAddress("localhost", sslConnector.getLocalPort()));

            HttpTester.Request request = HttpTester.newRequest();
            request.setURI(contextPath + path);
            OutputStream output = sslSocket.getOutputStream();
            output.write(BufferUtil.toArray(request.generate()));
            output.flush();

            HttpTester.Response response = HttpTester.parseResponse(sslSocket.getInputStream());
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());
        }
    }

    public static class ExamplePhpHandler extends Handler.Abstract
    {
        @Override
        public Request.Processor handle(Request request) throws Exception
        {
            return new Handler.Processor()
            {
                @Override
                public void process(Request request, Response response, Callback callback)
                {
                    response.setStatus(HttpStatus.OK_200);
                    response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain; charset=utf-8");

                    String message = "Example PHP: pathInContext=%s, query=%s".formatted(Request.getPathInContext(request), request.getHttpURI().getQuery());

                    response.write(true, BufferUtil.toBuffer(message, StandardCharsets.UTF_8), callback);
                }
            };
        }
    }

    public static class NoContentHandler extends Handler.Abstract
    {
        @Override
        public Request.Processor handle(Request request) throws Exception
        {
            return new Handler.Processor()
            {
                public void process(Request request, Response response, Callback callback)
                {
                    assertThat(Request.getPathInContext(request), equalTo("/forward"));
                    assertThat(request.getHttpURI().getQuery(), equalTo("p=/last"));
                    response.setStatus(HttpStatus.NO_CONTENT_204);
                    callback.succeeded();
                }
            };
        }
    }
}
