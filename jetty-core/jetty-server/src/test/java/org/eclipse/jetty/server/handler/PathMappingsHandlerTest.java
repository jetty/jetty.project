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

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.pathmap.ServletPathSpec;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PathMappingsHandlerTest
{
    private Server server;
    private LocalConnector connector;

    public void startServer(Handler handler) throws Exception
    {
        server = new Server();
        connector = new LocalConnector(server);
        server.addConnector(connector);

        server.addHandler(handler);
        server.start();
    }

    @AfterEach
    public void stopServer()
    {
        LifeCycle.stop(server);
    }

    public HttpTester.Response executeRequest(String rawRequest) throws Exception
    {
        String rawResponse = connector.getResponse(rawRequest);
        return HttpTester.parseResponse(rawResponse);
    }

    /**
     * Test where there are no mappings, and no wrapper.
     */
    @Test
    public void testEmpty() throws Exception
    {
        ContextHandler contextHandler = new ContextHandler();
        contextHandler.setContextPath("/");

        PathMappingsHandler pathMappingsHandler = new PathMappingsHandler();
        contextHandler.setHandler(pathMappingsHandler);

        startServer(contextHandler);

        HttpTester.Response response = executeRequest("""
            GET / HTTP/1.1\r
            Host: local\r
            Connection: close\r
             
            """);
        assertEquals(HttpStatus.NOT_FOUND_404, response.getStatus());
    }

    /**
     * Test where there are no mappings, and only a wrapper.
     */
    @Test
    public void testOnlyWrapper() throws Exception
    {
        ContextHandler contextHandler = new ContextHandler();
        contextHandler.setContextPath("/");

        PathMappingsHandler pathMappingsHandler = new PathMappingsHandler();
        pathMappingsHandler.setHandler(new SimpleHandler("WrapperFoo Hit"));
        contextHandler.setHandler(pathMappingsHandler);

        startServer(contextHandler);

        HttpTester.Response response = executeRequest("""
            GET / HTTP/1.1\r
            Host: local\r
            Connection: close\r
             
            """);
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertEquals("WrapperFoo Hit", response.getContent());
    }

    /**
     * Test where there is only a single mapping, and no wrapper.
     */
    @Test
    public void testOnlyMappingSuffix() throws Exception
    {
        ContextHandler contextHandler = new ContextHandler();
        contextHandler.setContextPath("/");

        PathMappingsHandler pathMappingsHandler = new PathMappingsHandler();
        pathMappingsHandler.addMapping(new ServletPathSpec("*.php"), new SimpleHandler("PhpExample Hit"));
        contextHandler.setHandler(pathMappingsHandler);

        startServer(contextHandler);

        HttpTester.Response response = executeRequest("""
            GET /hello HTTP/1.1\r
            Host: local\r
            Connection: close\r
             
            """);
        assertEquals(HttpStatus.NOT_FOUND_404, response.getStatus());

        response = executeRequest("""
            GET /hello.php HTTP/1.1\r
            Host: local\r
            Connection: close\r
             
            """);
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertEquals("PhpExample Hit", response.getContent());
    }

    /**
     * Test where there is only a single mapping, and a wrapper for fallback.
     */
    @Test
    public void testOneMappingAndWrapper() throws Exception
    {
        ContextHandler contextHandler = new ContextHandler();
        contextHandler.setContextPath("/");

        PathMappingsHandler pathMappingsHandler = new PathMappingsHandler();
        pathMappingsHandler.setHandler(new SimpleHandler("WrapperFoo Hit"));
        pathMappingsHandler.addMapping(new ServletPathSpec("*.php"), new SimpleHandler("PhpExample Hit"));
        contextHandler.setHandler(pathMappingsHandler);

        startServer(contextHandler);

        HttpTester.Response response = executeRequest("""
            GET /hello HTTP/1.1\r
            Host: local\r
            Connection: close\r
             
            """);
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertEquals("WrapperFoo Hit", response.getContent());

        response = executeRequest("""
            GET /hello.php HTTP/1.1\r
            Host: local\r
            Connection: close\r
             
            """);
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertEquals("PhpExample Hit", response.getContent());
    }

    public static Stream<Arguments> severalMappingsInput()
    {
        return Stream.of(
            Arguments.of("/hello", HttpStatus.OK_200, "FakeResourceHandler Hit"),
            Arguments.of("/index.html", HttpStatus.OK_200, "FakeSpecificStaticHandler Hit"),
            Arguments.of("/index.php", HttpStatus.OK_200, "PhpHandler Hit"),
            Arguments.of("/config.php", HttpStatus.OK_200, "PhpHandler Hit"),
            Arguments.of("/css/main.css", HttpStatus.OK_200, "FakeResourceHandler Hit")
        );
    }

    /**
     * Test where there are a few mappings, with a root mapping, and no wrapper.
     * This means the wrapper would not ever be hit, as all inputs would match at
     * least 1 mapping.
     */
    @ParameterizedTest
    @MethodSource("severalMappingsInput")
    public void testSeveralMappingAndNoWrapper(String requestPath, int expectedStatus, String expectedResponseBody) throws Exception
    {
        ContextHandler contextHandler = new ContextHandler();
        contextHandler.setContextPath("/");

        PathMappingsHandler pathMappingsHandler = new PathMappingsHandler();
        pathMappingsHandler.addMapping(new ServletPathSpec("/"), new SimpleHandler("FakeResourceHandler Hit"));
        pathMappingsHandler.addMapping(new ServletPathSpec("/index.html"), new SimpleHandler("FakeSpecificStaticHandler Hit"));
        pathMappingsHandler.addMapping(new ServletPathSpec("*.php"), new SimpleHandler("PhpHandler Hit"));
        contextHandler.setHandler(pathMappingsHandler);

        startServer(contextHandler);

        HttpTester.Response response = executeRequest("""
            GET %s HTTP/1.1\r
            Host: local\r
            Connection: close\r
             
            """.formatted(requestPath));
        assertEquals(expectedStatus, response.getStatus());
        assertEquals(expectedResponseBody, response.getContent());
    }

    private static class SimpleHandler extends Handler.Processor
    {
        private final String message;

        public SimpleHandler(String message)
        {
            this.message = message;
        }

        @Override
        public void process(Request request, Response response, Callback callback)
        {
            assertTrue(isStarted());
            response.setStatus(HttpStatus.OK_200);
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain; charset=utf-8");
            response.write(true, BufferUtil.toBuffer(message, StandardCharsets.UTF_8), callback);
        }
    }
}
