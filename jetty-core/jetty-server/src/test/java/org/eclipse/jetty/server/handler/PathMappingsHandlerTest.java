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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PathMappingsHandlerTest
{
    private Server server;
    private LocalConnector connector;

    public void startServer(Consumer<Server> configureServer) throws Exception
    {
        server = new Server();
        connector = new LocalConnector(server);
        server.addConnector(connector);

        configureServer.accept(server);

        server.start();
    }

    public void startServer(Handler handler) throws Exception
    {
        startServer((server) ->
        {
            server.setHandler(handler);
        });
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
     * Test where there are a few mappings, with a root mapping, and a ContextHandler
     * wrapping the PathMappingsHandler.
     */
    @ParameterizedTest
    @MethodSource("severalMappingsInput")
    public void testSeveralMappingWithContextHandler(String requestPath, int expectedStatus, String expectedResponseBody) throws Exception
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

    /**
     * Test where there are a few mappings, with a root mapping, and NO ContextHandler
     * wrapping the PathMappingsHandler.
     */
    @ParameterizedTest
    @MethodSource("severalMappingsInput")
    public void testSeveralMappingNoContextHandler(String requestPath, int expectedStatus, String expectedResponseBody) throws Exception
    {
        PathMappingsHandler pathMappingsHandler = new PathMappingsHandler();
        pathMappingsHandler.addMapping(new ServletPathSpec("/"), new SimpleHandler("FakeResourceHandler Hit"));
        pathMappingsHandler.addMapping(new ServletPathSpec("/index.html"), new SimpleHandler("FakeSpecificStaticHandler Hit"));
        pathMappingsHandler.addMapping(new ServletPathSpec("*.php"), new SimpleHandler("PhpHandler Hit"));

        startServer(pathMappingsHandler);

        HttpTester.Response response = executeRequest("""
            GET %s HTTP/1.1\r
            Host: local\r
            Connection: close\r
             
            """.formatted(requestPath));
        assertEquals(expectedStatus, response.getStatus());
        assertEquals(expectedResponseBody, response.getContent());
    }

    @Test
    public void testDump() throws Exception
    {
        ContextHandler contextHandler = new ContextHandler();
        contextHandler.setContextPath("/");

        PathMappingsHandler pathMappingsHandler = new PathMappingsHandler();
        pathMappingsHandler.addMapping(new ServletPathSpec("/"), new SimpleHandler("FakeResourceHandler Hit"));
        pathMappingsHandler.addMapping(new ServletPathSpec("/index.html"), new SimpleHandler("FakeSpecificStaticHandler Hit"));
        pathMappingsHandler.addMapping(new ServletPathSpec("*.php"), new SimpleHandler("PhpHandler Hit"));
        contextHandler.setHandler(pathMappingsHandler);

        startServer(contextHandler);

        String dump = contextHandler.dump();
        assertThat(dump, containsString("FakeResourceHandler"));
        assertThat(dump, containsString("FakeSpecificStaticHandler"));
        assertThat(dump, containsString("PhpHandler"));
        assertThat(dump, containsString("PathMappings[size=3]"));
    }

    /**
     * Test the updateHandler logic in regard to setServer() on added handlers.
     *
     * Sets the server handler first, then adds a handler.
     */
    @Test
    public void testServerNotNullSetHandlerThenAddMapping() throws Exception
    {
        SimpleHandler simpleHandler = new SimpleHandler("default");
        SimpleHandler extraHandler = new SimpleHandler("extra");

        startServer((server) ->
        {
            PathMappingsHandler pathMappingsHandler = new PathMappingsHandler();
            server.setHandler(pathMappingsHandler);
            pathMappingsHandler.addMapping(new ServletPathSpec("/"), simpleHandler);
            pathMappingsHandler.addMapping(new ServletPathSpec("/extras/*"), extraHandler);
        });

        assertNotNull(simpleHandler.getServer(), "Server was not set on start");
        assertNotNull(extraHandler.getServer(), "Server was not set on start");
    }

    /**
     * Test the updateHandler logic in regard to setServer() on added handlers.
     *
     * Add a handler(s) first, then sets the server handler.
     */
    @Test
    public void testServerNotNullAddMappingThenSetHandler() throws Exception
    {
        SimpleHandler simpleHandler = new SimpleHandler("default");
        SimpleHandler extraHandler = new SimpleHandler("extra");

        startServer((server) ->
        {
            PathMappingsHandler pathMappingsHandler = new PathMappingsHandler();
            pathMappingsHandler.addMapping(new ServletPathSpec("/"), simpleHandler);
            pathMappingsHandler.addMapping(new ServletPathSpec("/extras/*"), extraHandler);
            server.setHandler(pathMappingsHandler);
        });

        assertNotNull(simpleHandler.getServer(), "Server was not set on start");
        assertNotNull(extraHandler.getServer(), "Server was not set on start");
    }

    @Test
    public void testGetDescendantsDeep()
    {
        ContextHandler contextHandler = new ContextHandler();
        contextHandler.setContextPath("/");

        Handler.Sequence sequence = new Handler.Sequence();
        sequence.addHandler(new SimpleHandler("phpIndex"));
        Handler.Singleton handlerWrapper = new Handler.Wrapper(new SimpleHandler("other"));
        sequence.addHandler(handlerWrapper);

        PathMappingsHandler pathMappingsHandler = new PathMappingsHandler();
        pathMappingsHandler.addMapping(new ServletPathSpec("/"), new SimpleHandler("default"));
        pathMappingsHandler.addMapping(new ServletPathSpec("/index.html"), new SimpleHandler("specific"));
        pathMappingsHandler.addMapping(new ServletPathSpec("*.php"), sequence);

        List<String> actualHandlers = pathMappingsHandler.getDescendants().stream().map(Objects::toString).toList();

        String[] expectedHandlers = {
            "SimpleHandler[msg=\"default\"]",
            "SimpleHandler[msg=\"specific\"]",
            sequence.toString(),
            handlerWrapper.toString(),
            "SimpleHandler[msg=\"phpIndex\"]",
            "SimpleHandler[msg=\"other\"]"
        };
        assertThat(actualHandlers, containsInAnyOrder(expectedHandlers));
    }

    @Test
    public void testAddLoopSelf()
    {
        PathMappingsHandler pathMappingsHandler = new PathMappingsHandler();
        assertThrows(IllegalStateException.class, () -> pathMappingsHandler.addMapping(new ServletPathSpec("/self"), pathMappingsHandler));
    }

    @Test
    public void testAddLoopContext()
    {
        ContextHandler contextHandler = new ContextHandler();
        PathMappingsHandler pathMappingsHandler = new PathMappingsHandler();
        contextHandler.setHandler(pathMappingsHandler);

        assertThrows(IllegalStateException.class, () -> pathMappingsHandler.addMapping(new ServletPathSpec("/loop"), contextHandler));
    }

    private static class SimpleHandler extends Handler.Abstract
    {
        private final String message;

        public SimpleHandler(String message)
        {
            this.message = message;
        }

        @Override
        public boolean handle(Request request, Response response, Callback callback)
        {
            assertTrue(isStarted());
            response.setStatus(HttpStatus.OK_200);
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain; charset=utf-8");
            response.write(true, BufferUtil.toBuffer(message, StandardCharsets.UTF_8), callback);
            return true;
        }

        @Override
        public String toString()
        {
            return String.format("%s[msg=\"%s\"]", SimpleHandler.class.getSimpleName(), message);
        }
    }
}
