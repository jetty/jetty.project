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

package org.eclipse.jetty.server.handler;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import javax.net.ssl.SSLSocket;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.content.HttpContent;
import org.eclipse.jetty.http.content.ResourceHttpContentFactory;
import org.eclipse.jetty.http.pathmap.ServletPathSpec;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(WorkDirExtension.class)
public class TryPathsHandlerTest
{
    private static final String CONTEXT_PATH = "/ctx";
    private Server server;
    private SslContextFactory.Server sslContextFactory;
    private ServerConnector connector;
    private ServerConnector sslConnector;
    private TryPathsHandler tryPathsHandler;

    private void start(List<String> paths, Handler handler, Path tmpPath) throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server, 1, 1);
        server.addConnector(connector);

        sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath("src/test/resources/keystore.p12");
        sslContextFactory.setKeyStorePassword("storepwd");
        sslConnector = new ServerConnector(server, 1, 1, sslContextFactory);
        server.addConnector(sslConnector);

        ContextHandler context = new ContextHandler(CONTEXT_PATH);
        context.setBaseResourceAsPath(tmpPath);
        server.setHandler(context);

        tryPathsHandler = new TryPathsHandler();
        context.setHandler(tryPathsHandler);

        tryPathsHandler.setPaths(paths);
        tryPathsHandler.setHandler(handler);

        server.start();
    }

    @AfterEach
    public void dispose()
    {
        LifeCycle.stop(server);
    }

    @Test
    public void testTryPaths(WorkDir workDir) throws Exception
    {
        Path tmpPath = workDir.getEmptyPathDir();
        ResourceHandler resourceHandler = new ResourceHandler()
        {
            @Override
            protected HttpContent.Factory newHttpContentFactory()
            {
                // We don't want to cache not found entries for this test.
                return new ResourceHttpContentFactory(getBaseResource(), getMimeTypes());
            }
        };

        resourceHandler.setDirAllowed(false);
        resourceHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                if (!Request.getPathInContext(request).startsWith("/forward"))
                    return false;

                assertThat(Request.getPathInContext(request), equalTo("/forward"));
                assertThat(request.getHttpURI().getQuery(), equalTo("p=/last"));
                response.setStatus(HttpStatus.NO_CONTENT_204);
                callback.succeeded();
                return true;
            }
        });

        start(List.of("/maintenance.txt", "$path", "/forward?p=$path"), resourceHandler, tmpPath);

        try (SocketChannel channel = SocketChannel.open())
        {
            channel.connect(new InetSocketAddress("localhost", connector.getLocalPort()));

            // Make a first request without existing file paths.
            HttpTester.Request request = HttpTester.newRequest();
            request.setURI(CONTEXT_PATH + "/last");
            channel.write(request.generate());
            HttpTester.Response response = HttpTester.parseResponse(channel);
            assertNotNull(response);
            assertEquals(HttpStatus.NO_CONTENT_204, response.getStatus());

            // Create the specific static file that is requested.
            String path = "idx.txt";
            Files.writeString(tmpPath.resolve(path), "hello", StandardOpenOption.CREATE);
            // Make a second request with the specific file.
            request = HttpTester.newRequest();
            request.setURI(CONTEXT_PATH + "/" + path);
            channel.write(request.generate());
            response = HttpTester.parseResponse(channel);
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());
            assertEquals("hello", response.getContent());

            // Create the "maintenance" file, it should be served first.
            path = "maintenance.txt";
            Files.writeString(tmpPath.resolve(path), "maintenance", StandardOpenOption.CREATE);
            // Make a third request with any path, we should get the maintenance file.
            request = HttpTester.newRequest();
            request.setURI(CONTEXT_PATH + "/whatever");
            channel.write(request.generate());
            response = HttpTester.parseResponse(channel);
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());
            assertEquals("maintenance", response.getContent());
        }
    }

    @Test
    public void testTryPathsWithPathMappings(WorkDir workDir) throws Exception
    {
        Path tmpPath = workDir.getEmptyPathDir();
        ResourceHandler resourceHandler = new ResourceHandler()
        {
            @Override
            protected HttpContent.Factory newHttpContentFactory()
            {
                // We don't want to cache not found entries for this test.
                return new ResourceHttpContentFactory(getBaseResource(), getMimeTypes());
            }
        };
        resourceHandler.setDirAllowed(false);

        PathMappingsHandler pathMappingsHandler = new PathMappingsHandler();
        pathMappingsHandler.addMapping(new ServletPathSpec("/"), resourceHandler);
        pathMappingsHandler.addMapping(new ServletPathSpec("*.php"), new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                response.setStatus(HttpStatus.OK_200);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain; charset=utf-8");
                String message = "PHP: pathInContext=%s, query=%s".formatted(Request.getPathInContext(request), request.getHttpURI().getQuery());
                Content.Sink.write(response, true, message, callback);
                return true;
            }
        });
        pathMappingsHandler.addMapping(new ServletPathSpec("/forward"), new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                assertThat(Request.getPathInContext(request), equalTo("/forward"));
                assertThat(request.getHttpURI().getQuery(), equalTo("p=/last"));
                response.setStatus(HttpStatus.NO_CONTENT_204);
                callback.succeeded();
                return true;
            }
        });

        start(List.of("/maintenance.txt", "$path", "/forward?p=$path"), pathMappingsHandler, tmpPath);

        try (SocketChannel channel = SocketChannel.open())
        {
            channel.connect(new InetSocketAddress("localhost", connector.getLocalPort()));

            // Make a first request without existing file paths.
            HttpTester.Request request = HttpTester.newRequest();
            request.setURI(CONTEXT_PATH + "/last");
            channel.write(request.generate());
            HttpTester.Response response = HttpTester.parseResponse(channel);
            assertNotNull(response);
            assertEquals(HttpStatus.NO_CONTENT_204, response.getStatus());

            // Create the specific static file that is requested.
            String path = "idx.txt";
            Files.writeString(tmpPath.resolve(path), "hello", StandardOpenOption.CREATE);
            // Make a second request with the specific file.
            request = HttpTester.newRequest();
            request.setURI(CONTEXT_PATH + "/" + path);
            channel.write(request.generate());
            response = HttpTester.parseResponse(channel);
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());
            assertEquals("hello", response.getContent());

            // Request an existing PHP file.
            Files.writeString(tmpPath.resolve("index.php"), "raw-php-contents", StandardOpenOption.CREATE);
            request = HttpTester.newRequest();
            request.setURI(CONTEXT_PATH + "/index.php");
            channel.write(request.generate());
            response = HttpTester.parseResponse(channel);
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());
            assertThat(response.getContent(), startsWith("PHP: pathInContext=/index.php"));

            // Create the "maintenance" file, it should be served first.
            path = "maintenance.txt";
            Files.writeString(tmpPath.resolve(path), "maintenance", StandardOpenOption.CREATE);
            // Make a second request with any path, we should get the maintenance file.
            request = HttpTester.newRequest();
            request.setURI(CONTEXT_PATH + "/whatever");
            channel.write(request.generate());
            response = HttpTester.parseResponse(channel);
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());
            assertEquals("maintenance", response.getContent());
        }
    }

    @Test
    public void testSecureRequestIsForwarded(WorkDir workDir) throws Exception
    {
        Path tmpPath = workDir.getEmptyPathDir();
        String path = "/secure";
        start(List.of("$path"), new Handler.Abstract.NonBlocking()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                HttpURI httpURI = request.getHttpURI();
                assertEquals("https", httpURI.getScheme());
                assertTrue(request.isSecure());
                assertEquals(path, Request.getPathInContext(request));
                callback.succeeded();
                return true;
            }
        }, tmpPath);

        try (SSLSocket sslSocket = sslContextFactory.newSslSocket())
        {
            sslSocket.connect(new InetSocketAddress("localhost", sslConnector.getLocalPort()));

            HttpTester.Request request = HttpTester.newRequest();
            request.setURI(CONTEXT_PATH + path);
            OutputStream output = sslSocket.getOutputStream();
            output.write(BufferUtil.toArray(request.generate()));
            output.flush();

            HttpTester.Response response = HttpTester.parseResponse(sslSocket.getInputStream());
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());
        }
    }

    @Test
    public void testTryPathsHandlerAttributes(WorkDir workDir) throws Exception
    {
        String pathAttribute = "test.path";
        String queryAttribute = "test.query";
        ResourceHandler resourceHandler = new ResourceHandler()
        {
            @Override
            protected HttpContent.Factory newHttpContentFactory()
            {
                // We don't want to cache not found entries for this test.
                return new ResourceHttpContentFactory(getBaseResource(), getMimeTypes());
            }
        };
        resourceHandler.setDirAllowed(false);
        resourceHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                String pathInContext = Request.getPathInContext(request);
                assertEquals("/index.php", pathInContext);

                Object originalPath = request.getAttribute(pathAttribute);
                assertNotNull(originalPath);
                assertEquals("/hello/", originalPath);

                Object originalQuery = request.getAttribute(queryAttribute);
                assertNotNull(originalQuery);
                assertEquals("a=b&c=%2F", originalQuery);

                response.setStatus(HttpStatus.NO_CONTENT_204);
                callback.succeeded();
                return true;
            }
        });

        start(List.of("/index.php"), resourceHandler, workDir.getEmptyPathDir());
        tryPathsHandler.setOriginalPathAttribute(pathAttribute);
        tryPathsHandler.setOriginalQueryAttribute(queryAttribute);

        try (SocketChannel channel = SocketChannel.open())
        {
            channel.connect(new InetSocketAddress("localhost", connector.getLocalPort()));

            HttpTester.Request request = HttpTester.newRequest();
            request.setURI(CONTEXT_PATH + "/hello/?a=b&c=%2F");
            channel.write(request.generate());
            HttpTester.Response response = HttpTester.parseResponse(channel);
            assertNotNull(response);
            assertEquals(HttpStatus.NO_CONTENT_204, response.getStatus());
        }
    }
}
