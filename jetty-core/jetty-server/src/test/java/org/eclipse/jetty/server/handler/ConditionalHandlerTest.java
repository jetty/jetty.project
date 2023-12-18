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

import java.util.function.Predicate;
import java.util.stream.Stream;

import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.server.ForwardedRequestCustomizer;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.not;

public class ConditionalHandlerTest
{
    private Server _server;
    private LocalConnector _connector;
    private HelloHandler _helloHandler;
    private Expected _expected;

    @BeforeEach
    public void beforeEach() throws Exception
    {
        _server = new Server();
        _connector = new LocalConnector(_server);
        _connector.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration().addCustomizer(new ForwardedRequestCustomizer());
        _server.addConnector(_connector);
        _helloHandler = new HelloHandler();
    }

    @AfterEach
    public void afterEach() throws Exception
    {
        _server.stop();
    }

    private void startServer(Handler.Singleton testHandler) throws Exception
    {
        _expected = (Expected)testHandler;
        _server.setHandler(testHandler);
        testHandler.getTail().setHandler(_helloHandler);
        _server.start();
    }

    public static Stream<ConditionalHandler> conditionalHandlers()
    {
        return Stream.of(
            new TestConditionalHandler(),
            new TestConditionalHandlerSkipNext(new TestHandler()),
            new TestConditionalHandlerDontHandle(new TestHandler()),
            new TestConditionalHandlerReject(new TestHandler())
        );
    }

    @ParameterizedTest
    @MethodSource("conditionalHandlers")
    public void testNoConditions(ConditionalHandler testHandler) throws Exception
    {
        startServer(testHandler);
        Expected expected = (Expected)testHandler;

        String response = _connector.getResponse("GET / HTTP/1.0\n\n");
        expected.testDoHandle(response);

        response = _connector.getResponse("POST /foo HTTP/1.0\n\n");
        expected.testDoHandle(response);
    }

    @ParameterizedTest
    @MethodSource("conditionalHandlers")
    public void testMethod(ConditionalHandler testHandler) throws Exception
    {
        testHandler.includeMethod("GET");
        testHandler.excludeMethod("POST");
        startServer(testHandler);

        String response = _connector.getResponse("GET / HTTP/1.0\n\n");
        _expected.testDoHandle(response);

        response = _connector.getResponse("POST /foo HTTP/1.0\n\n");
        _expected.testDoNotHandle(response);
    }

    @ParameterizedTest
    @MethodSource("conditionalHandlers")
    public void testPath(ConditionalHandler testHandler) throws Exception
    {
        testHandler.includePath("/foo/*");
        testHandler.excludePath("/foo/bar");
        startServer(testHandler);
        String response = _connector.getResponse("GET /foo HTTP/1.0\n\n");
        _expected.testDoHandle(response);

        response = _connector.getResponse("POST /foo/bar HTTP/1.0\n\n");
        _expected.testDoNotHandle(response);
    }

    @ParameterizedTest
    @MethodSource("conditionalHandlers")
    public void testInet(ConditionalHandler testHandler) throws Exception
    {
        testHandler.includeInetAddressPattern("192.168.128.0-192.168.128.128");
        testHandler.excludeInetAddressPattern("192.168.128.30-192.168.128.39");
        startServer(testHandler);
        String response = _connector.getResponse("""
            GET /foo HTTP/1.0
            Forwarded: for=192.168.128.1
            
            """);
        _expected.testDoHandle(response);

        response = _connector.getResponse("""
            GET /foo HTTP/1.0
            Forwarded: for=192.168.128.31
            
            """);
        _expected.testDoNotHandle(response);
    }

    @ParameterizedTest
    @MethodSource("conditionalHandlers")
    public void testMethodPath(ConditionalHandler testHandler) throws Exception
    {
        testHandler.includeMethod("GET");
        testHandler.excludeMethod("POST");
        testHandler.includePath("/foo/*");
        testHandler.excludePath("/foo/bar");
        startServer(testHandler);
        String response = _connector.getResponse("GET /foo HTTP/1.0\n\n");
        _expected.testDoHandle(response);

        response = _connector.getResponse("GET /foo/bar HTTP/1.0\n\n");
        _expected.testDoNotHandle(response);

        response = _connector.getResponse("POST /foo HTTP/1.0\n\n");
        _expected.testDoNotHandle(response);

        response = _connector.getResponse("POST /foo/bar HTTP/1.0\n\n");
        _expected.testDoNotHandle(response);
    }

    @Test
    public void testMethodPredicateOptimization()
    {
        Predicate<Request> predicate = ConditionalHandler.from(null, null, "GET", (String)null);
        assertThat(predicate, instanceOf(ConditionalHandler.MethodPredicate.class));
        ConditionalHandler conditionalHandler = new ConditionalHandler.DontHandle();
        conditionalHandler.include(predicate);
        assertThat(conditionalHandler.getMethods().getIncluded(), hasSize(1));
        assertThat(conditionalHandler.getPredicates().getIncluded(), hasSize(0));
    }

    @Test
    public void testPathSpecPredicateOptimization()
    {
        Predicate<Request> predicate = ConditionalHandler.from(null, null, null, PathSpec.from("/*"));
        assertThat(predicate, instanceOf(ConditionalHandler.PathSpecPredicate.class));
        ConditionalHandler conditionalHandler = new ConditionalHandler.DontHandle();
        conditionalHandler.include(predicate);
        assertThat(conditionalHandler.getPathSpecs().getIncluded(), hasSize(1));
        assertThat(conditionalHandler.getPredicates().getIncluded(), hasSize(0));
    }

    interface Expected
    {
        void testDoHandle(String response);

        default void testDoNotHandle(String response)
        {
            assertThat(response, containsString("200 OK"));
            assertThat(response, containsString("Test: applied"));
        }
    }

    public static class TestConditionalHandler extends ConditionalHandler.Abstract implements Expected
    {
        @Override
        public boolean onConditionsMet(Request request, Response response, Callback callback) throws Exception
        {
            response.getHeaders().put("Test", "applied");
            return nextHandler(request, response, callback);
        }

        @Override
        protected boolean onConditionsNotMet(Request request, Response response, Callback callback) throws Exception
        {
            return nextHandler(request, response, callback);
        }

        public void testDoHandle(String response)
        {
            assertThat(response, containsString("200 OK"));
            assertThat(response, containsString("Test: applied"));
        }

        public void testDoNotHandle(String response)
        {
            assertThat(response, containsString("200 OK"));
            assertThat(response, not(containsString("Test: applied")));
        }

    }

    public static class TestConditionalHandlerSkipNext extends ConditionalHandler.SkipNext implements Expected
    {
        TestConditionalHandlerSkipNext(Handler handler)
        {
            super(handler);
        }

        public void testDoHandle(String response)
        {
            assertThat(response, containsString("200 OK"));
            assertThat(response, not(containsString("Test: applied")));
        }
    }

    public static class TestConditionalHandlerDontHandle extends ConditionalHandler.DontHandle implements Expected
    {
        TestConditionalHandlerDontHandle(Handler handler)
        {
            super(handler);
        }

        public void testDoHandle(String response)
        {
            assertThat(response, containsString("404 Not Found"));
            assertThat(response, not(containsString("Test: applied")));
        }
    }

    public static class TestConditionalHandlerReject extends ConditionalHandler.Reject implements Expected
    {
        TestConditionalHandlerReject(Handler handler)
        {
            super(handler);
        }

        public void testDoHandle(String response)
        {
            assertThat(response, containsString("403 Forbidden"));
            assertThat(response, not(containsString("Test: applied")));
        }
    }
    
    public static class TestHandler extends Handler.Wrapper
    {
        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            response.getHeaders().put("Test", "applied");
            return super.handle(request, response, callback);
        }
    }

}
