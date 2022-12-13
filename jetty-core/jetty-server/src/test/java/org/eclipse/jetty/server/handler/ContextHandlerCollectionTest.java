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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

public class ContextHandlerCollectionTest
{
    public static Stream<Arguments> virtualHostCases()
    {
        return Stream.of(
            Arguments.of(0, "www.example.com.", "/ctx", "-", HttpStatus.MOVED_PERMANENTLY_301),
            Arguments.of(0, "www.example.com.", "/ctx/", "A", HttpStatus.OK_200),
            Arguments.of(0, "www.example.com.", "/ctx/info", "A", HttpStatus.OK_200),
            Arguments.of(0, "www.example.com", "/ctx/info", "A", HttpStatus.OK_200),
            Arguments.of(0, "alias.example.com", "/ctx/info", "A", HttpStatus.OK_200),
            Arguments.of(1, "www.example.com.", "/ctx/info", "A", HttpStatus.OK_200),
            Arguments.of(1, "www.example.com", "/ctx/info", "A", HttpStatus.OK_200),
            Arguments.of(1, "alias.example.com", "/ctx/info", "A", HttpStatus.OK_200),

            Arguments.of(1, "www.other.com", "/ctx", "-", HttpStatus.MOVED_PERMANENTLY_301),
            Arguments.of(1, "www.other.com", "/ctx/", "B", HttpStatus.OK_200),
            Arguments.of(1, "www.other.com", "/ctx/info", "B", HttpStatus.OK_200),
            Arguments.of(0, "www.other.com", "/ctx/info", "C", HttpStatus.OK_200),

            Arguments.of(0, "www.example.com", "/ctxinfo", "D", HttpStatus.OK_200),
            Arguments.of(1, "unknown.com", "/unknown", "D", HttpStatus.OK_200),

            Arguments.of(0, "alias.example.com", "/ctx/foo/info", "E", HttpStatus.OK_200),
            Arguments.of(0, "alias.example.com", "/ctxlong/info", "F", HttpStatus.OK_200)
        );
    }

    @ParameterizedTest
    @MethodSource("virtualHostCases")
    public void testVirtualHosts(int useConnectorNum, String host, String uri, String handlerRef, int expectedStatus, TestInfo testInfo) throws Exception
    {
        Server server = new Server();
        LocalConnector connector0 = new LocalConnector(server);
        LocalConnector connector1 = new LocalConnector(server);
        connector1.setName("connector1");

        server.addConnector(connector0);
        server.addConnector(connector1);

        ContextHandler contextA = new ContextHandler("/ctx");
        contextA.setVirtualHosts(List.of("www.example.com", "alias.example.com"));
        contextA.setHandler(new IsHandledHandler("A"));

        ContextHandler contextB = new ContextHandler("/ctx");
        contextB.setHandler(new IsHandledHandler("B"));
        contextB.setVirtualHosts(List.of("*.other.com@connector1"));

        ContextHandler contextC = new ContextHandler("/ctx");
        contextC.setHandler(new IsHandledHandler("C"));

        ContextHandler contextD = new ContextHandler("/");
        contextD.setHandler(new IsHandledHandler("D"));

        ContextHandler contextE = new ContextHandler("/ctx/foo");
        contextE.setHandler(new IsHandledHandler("E"));

        ContextHandler contextF = new ContextHandler("/ctxlong");
        contextF.setHandler(new IsHandledHandler("F"));

        ContextHandlerCollection c = new ContextHandlerCollection();
        c.addHandler(contextA);
        c.addHandler(contextB);
        c.addHandler(contextC);

        Handler.Collection handlers = new Handler.Collection();
        handlers.addHandler(contextE);
        handlers.addHandler(contextF);
        handlers.addHandler(contextD);
        c.addHandler(handlers);

        server.setHandler(c);

        try
        {
            server.start();

            LocalConnector connector = switch (useConnectorNum)
                {
                    case 0 -> connector0;
                    case 1 -> connector1;
                    default -> fail("Unsupported connector number: " + useConnectorNum);
                };

            String rawRequest = ("""
                GET %s HTTP/1.1\r
                Host: %s\r
                Connection: close\r
                \r
                """).formatted(uri, host);

            String rawResponse = connector.getResponse(rawRequest);
            HttpTester.Response response = HttpTester.parseResponse(rawResponse);

            assertThat(testInfo.getDisplayName(), response.getStatus(), is(expectedStatus));

            if (response.getStatus() == HttpStatus.OK_200)
            {
                assertThat(testInfo.getDisplayName(), response.getContent(), endsWith(handlerRef));
                assertThat(testInfo.getDisplayName(), response.get("X-IsHandled-Name"), is(handlerRef));
            }
        }
        finally
        {
            server.stop();
        }
    }

    public static Stream<Arguments> virtualHostWildcardMatchedCases()
    {
        List<Arguments> args = new ArrayList<>();

        List<String> contextHosts;

        contextHosts = List.of();
        args.add(Arguments.of(contextHosts, "example.com"));
        args.add(Arguments.of(contextHosts, ".example.com"));
        args.add(Arguments.of(contextHosts, "vhost.example.com"));

        contextHosts = List.of("example.com", "*.example.com");
        args.add(Arguments.of(contextHosts, "example.com"));
        args.add(Arguments.of(contextHosts, ".example.com"));
        args.add(Arguments.of(contextHosts, "vhost.example.com"));

        contextHosts = List.of("*.example.com");
        args.add(Arguments.of(contextHosts, "vhost.example.com"));
        args.add(Arguments.of(contextHosts, ".example.com"));

        contextHosts = List.of("*.sub.example.com");
        args.add(Arguments.of(contextHosts, "vhost.sub.example.com"));
        args.add(Arguments.of(contextHosts, ".sub.example.com"));

        return args.stream();
    }

    @ParameterizedTest
    @MethodSource("virtualHostWildcardMatchedCases")
    public void testVirtualHostWildcardMatched(List<String> contextHosts, String requestHost) throws Exception
    {
        Server server = new Server();
        LocalConnector connector = new LocalConnector(server);
        server.setConnectors(new Connector[]{connector});

        ContextHandler context = new ContextHandler("/");
        context.setVirtualHosts(contextHosts);
        context.setHandler(new IsHandledHandler("H"));

        ContextHandlerCollection c = new ContextHandlerCollection();
        c.addHandler(context);

        server.setHandler(c);

        try
        {
            server.start();

            String rawRequest = """
                GET / HTTP/1.1\r
                Host: %s\r
                Connection:close\r
                \r
                """.formatted(requestHost);

            String rawResponse = connector.getResponse(rawRequest);
            HttpTester.Response response = HttpTester.parseResponse(rawResponse);
            assertThat("Response status for [GET " + requestHost + "]", response.getStatus(), is(HttpStatus.OK_200));
            assertThat("Response body for [GET " + requestHost + "]", response.getContent(), is("H"));
            assertThat("Response Header for [GET " + requestHost + "]", response.get("X-IsHandled-Name"), is("H"));
        }
        finally
        {
            server.stop();
        }
    }

    public static Stream<Arguments> virtualHostWildcardNoMatchCases()
    {
        List<Arguments> args = new ArrayList<>();

        List<String> contextHosts;

        contextHosts = List.of("example.com", "*.example.com");
        args.add(Arguments.of(contextHosts, "badexample.com"));
        args.add(Arguments.of(contextHosts, ".badexample.com"));
        args.add(Arguments.of(contextHosts, "vhost.badexample.com"));

        contextHosts = List.of("*.");
        args.add(Arguments.of(contextHosts, "anything.anything"));

        contextHosts = List.of("*.example.com");
        args.add(Arguments.of(contextHosts, "vhost.www.example.com"));
        args.add(Arguments.of(contextHosts, "example.com"));
        args.add(Arguments.of(contextHosts, "www.vhost.example.com"));

        contextHosts = List.of("*.sub.example.com");
        args.add(Arguments.of(contextHosts, ".example.com"));
        args.add(Arguments.of(contextHosts, "sub.example.com"));
        args.add(Arguments.of(contextHosts, "vhost.example.com"));

        contextHosts = List.of("example.*.com", "example.com.*");
        args.add(Arguments.of(contextHosts, "example.vhost.com"));
        args.add(Arguments.of(contextHosts, "example.com.vhost"));
        args.add(Arguments.of(contextHosts, "example.com"));

        return args.stream();
    }

    @ParameterizedTest
    @MethodSource("virtualHostWildcardNoMatchCases")
    public void testVirtualHostWildcardNoMatch(List<String> contextHosts, String requestHost) throws Exception
    {
        Server server = new Server();
        LocalConnector connector = new LocalConnector(server);
        server.setConnectors(new Connector[]{connector});

        ContextHandler context = new ContextHandler("/");
        context.setVirtualHosts(contextHosts);

        IsHandledHandler handler = new IsHandledHandler("H");
        context.setHandler(handler);

        ContextHandlerCollection c = new ContextHandlerCollection();
        c.addHandler(context);

        server.setHandler(c);

        try
        {
            server.start();

            String rawRequest = """
                GET / HTTP/1.1\r
                Host: %s\r
                Connection:close\r
                \r
                """.formatted(requestHost);

            String rawResponse = connector.getResponse(rawRequest);
            HttpTester.Response response = HttpTester.parseResponse(rawResponse);
            assertThat("Response status for [GET " + requestHost + "]", response.getStatus(), is(HttpStatus.NOT_FOUND_404));
            assertThat("Response body for [GET " + requestHost + "]", response.getContent(), containsString("<h2>HTTP ERROR 404 Not Found</h2>"));
            assertThat("Response Header for [GET " + requestHost + "]", response.get("X-IsHandled-Name"), nullValue());

            connector.getResponse(rawRequest);
            assertFalse(handler.isHandled(), "'" + requestHost + "' should not have been handled.");
        }
        finally
        {
            server.stop();
        }
    }

    @Test
    public void testFindContainer()
    {
        Server server = new Server();

        ContextHandler contextA = new ContextHandler("/a");
        IsHandledHandler handlerA = new IsHandledHandler("A");
        contextA.setHandler(handlerA);

        ContextHandler contextB = new ContextHandler("/b");
        IsHandledHandler handlerB = new IsHandledHandler("B");
        Handler.Wrapper wrapperB = new Handler.Wrapper();
        wrapperB.setHandler(handlerB);
        contextB.setHandler(wrapperB);

        ContextHandler contextC = new ContextHandler("/c");
        IsHandledHandler handlerC = new IsHandledHandler("C");
        contextC.setHandler(handlerC);

        ContextHandlerCollection collection = new ContextHandlerCollection();

        collection.addHandler(contextA);
        collection.addHandler(contextB);
        collection.addHandler(contextC);

        Handler.Wrapper wrapper = new Handler.Wrapper();
        wrapper.setHandler(collection);
        server.setHandler(wrapper);

        assertEquals(wrapper, Handler.AbstractContainer.findContainerOf(server, Handler.Wrapper.class, handlerA));
        assertEquals(contextA, Handler.AbstractContainer.findContainerOf(server, ContextHandler.class, handlerA));
        assertEquals(contextB, Handler.AbstractContainer.findContainerOf(server, ContextHandler.class, handlerB));
        assertEquals(wrapper, Handler.AbstractContainer.findContainerOf(server, Handler.Wrapper.class, handlerB));
        assertEquals(contextB, Handler.AbstractContainer.findContainerOf(collection, Handler.Wrapper.class, handlerB));
        assertEquals(wrapperB, Handler.AbstractContainer.findContainerOf(contextB, Handler.Wrapper.class, handlerB));
    }

    @Test
    public void testWrappedContext() throws Exception
    {
        Server server = new Server();
        LocalConnector connector = new LocalConnector(server);
        server.setConnectors(new Connector[]{connector});

        ContextHandler root = new ContextHandler("/");
        root.setHandler(new IsHandledHandler("root"));

        ContextHandler left = new ContextHandler("/left");
        left.setHandler(new IsHandledHandler("left"));

        Handler.Collection centre = new Handler.Collection();
        ContextHandler centreLeft = new ContextHandler("/leftcentre");
        centreLeft.setHandler(new IsHandledHandler("left of centre"));
        ContextHandler centreRight = new ContextHandler("/rightcentre");
        centreRight.setHandler(new IsHandledHandler("right of centre"));
        centre.setHandlers(List.of(centreLeft, new WrappedHandler(centreRight, "centreRight")));

        ContextHandler right = new ContextHandler("/right");
        right.setHandler(new IsHandledHandler("right"));

        ContextHandlerCollection contexts = new ContextHandlerCollection();
        contexts.setHandlers(List.of(root, left, centre, new WrappedHandler(right, "right")));

        server.setHandler(contexts);
        server.start();

        String response;
        response = connector.getResponse("GET / HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, endsWith("root"));
        assertThat(response, not(containsString("Wrapped:")));

        response = connector.getResponse("GET /foobar/info HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, endsWith("root"));
        assertThat(response, not(containsString("Wrapped:")));

        response = connector.getResponse("GET /left/info HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, endsWith("left"));
        assertThat(response, not(containsString("Wrapped:")));

        response = connector.getResponse("GET /leftcentre/info HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, endsWith("left of centre"));
        assertThat(response, not(containsString("Wrapped:")));

        response = connector.getResponse("GET /rightcentre/info HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, endsWith("right of centre"));
        assertThat(response, containsString("Wrapped: centreRight"));

        response = connector.getResponse("GET /right/info HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, endsWith("right"));
        assertThat(response, containsString("Wrapped: right"));
    }

    private static final class WrappedHandler extends Handler.Wrapper
    {
        private final String tag;

        WrappedHandler(Handler handler, String tag)
        {
            this.tag = tag;
            setHandler(handler);
        }

        @Override
        public boolean process(Request request, Response response, Callback callback) throws Exception
        {
            response.getHeaders().put("Wrapped", tag);
            if (super.process(request, response, callback))
                return true;
            response.getHeaders().remove("Wrapped");
            return false;
        }
    }

    private static final class IsHandledHandler extends Handler.Abstract.Blocking
    {
        private boolean handled;
        private final String name;

        public IsHandledHandler(String string)
        {
            name = string;
        }

        public boolean isHandled()
        {
            return handled;
        }

        @Override
        public boolean process(Request request, Response response, Callback callback)
        {
            this.handled = true;
            response.getHeaders().put("X-IsHandled-Name", name);
            ByteBuffer nameBuffer = BufferUtil.toBuffer(name, StandardCharsets.UTF_8);
            response.write(true, nameBuffer, callback);
            return true;
        }

        public void reset()
        {
            handled = false;
        }

        @Override
        public String toString()
        {
            return name;
        }
    }
}
