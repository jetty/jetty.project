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

import java.util.stream.Stream;

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

public class ConditionalHandlerTest
{
    private Server _server;
    private LocalConnector _connector;
    private HelloHandler _helloHandler;

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
        _server.setHandler(testHandler);
        Handler tail = testHandler;
        while (tail instanceof Handler.Singleton singleton)
        {
            if (singleton.getHandler() == null)
            {
                singleton.setHandler(_helloHandler);
                break;
            }
            tail = singleton.getHandler();
        }
        _server.start();
    }

    public static Stream<ConditionalHandler> conditionalHandlers()
    {
        return Stream.of(
            new TestNextHandler(ConditionalHandler.NotMetAction.REJECT),
            new TestNextHandler(ConditionalHandler.NotMetAction.FORBIDDEN),
            new TestSkipThisHandler(),
            new TestNextHandler(ConditionalHandler.NotMetAction.SKIP_NEXT)
        );
    }

    @ParameterizedTest
    @MethodSource("conditionalHandlers")
    public void testMethod(TestConditionalHandler testHandler) throws Exception
    {
        testHandler.includeMethod("GET");
        testHandler.excludeMethod("POST");
        startServer(testHandler);
        String response = _connector.getResponse("GET / HTTP/1.0\n\n");
        assertThat(response, containsString("200 OK"));
        assertThat(response, containsString("Test: applied"));

        response = _connector.getResponse("POST /foo HTTP/1.0\n\n");
        assertThat(response, containsString(testHandler.getExpectedWhenNotApplied()));
        assertThat(response, not(containsString("Test: applied")));
    }

    @ParameterizedTest
    @MethodSource("conditionalHandlers")
    public void testPath(TestConditionalHandler testHandler) throws Exception
    {
        testHandler.includePath("/foo/*");
        testHandler.excludePath("/foo/bar");
        startServer(testHandler);
        String response = _connector.getResponse("GET /foo HTTP/1.0\n\n");
        assertThat(response, containsString("200 OK"));
        assertThat(response, containsString("Test: applied"));

        response = _connector.getResponse("POST /foo/bar HTTP/1.0\n\n");
        assertThat(response, containsString(testHandler.getExpectedWhenNotApplied()));
        assertThat(response, not(containsString("Test: applied")));
    }

    @ParameterizedTest
    @MethodSource("conditionalHandlers")
    public void testInet(TestConditionalHandler testHandler) throws Exception
    {
        testHandler.includeInetAddress("192.168.128.0-192.168.128.128");
        testHandler.excludeInetAddress("192.168.128.30-192.168.128.39");
        startServer(testHandler);
        String response = _connector.getResponse("""
            GET /foo HTTP/1.0
            Forwarded: for=192.168.128.1
            
            """);
        assertThat(response, containsString("200 OK"));
        assertThat(response, containsString("Test: applied"));
        response = _connector.getResponse("""
            GET /foo HTTP/1.0
            Forwarded: for=192.168.128.31
            
            """);
        assertThat(response, containsString(testHandler.getExpectedWhenNotApplied()));
        assertThat(response, not(containsString("Test: applied")));
    }

    @ParameterizedTest
    @MethodSource("conditionalHandlers")
    public void testMethodPath(TestConditionalHandler testHandler) throws Exception
    {
        testHandler.includeMethod("GET");
        testHandler.excludeMethod("POST");
        testHandler.includePath("/foo/*");
        testHandler.excludePath("/foo/bar");
        startServer(testHandler);
        String response = _connector.getResponse("GET /foo HTTP/1.0\n\n");
        assertThat(response, containsString("200 OK"));
        assertThat(response, containsString("Test: applied"));

        response = _connector.getResponse("GET /foo/bar HTTP/1.0\n\n");
        assertThat(response, containsString(testHandler.getExpectedWhenNotApplied()));
        assertThat(response, not(containsString("Test: applied")));

        response = _connector.getResponse("POST /foo HTTP/1.0\n\n");
        assertThat(response, containsString(testHandler.getExpectedWhenNotApplied()));
        assertThat(response, not(containsString("Test: applied")));

        response = _connector.getResponse("POST /foo/bar HTTP/1.0\n\n");
        assertThat(response, containsString(testHandler.getExpectedWhenNotApplied()));
        assertThat(response, not(containsString("Test: applied")));
    }

    public static class TestConditionalHandler extends ConditionalHandler
    {
        final String _expectedWhenNotApplied;

        public TestConditionalHandler(ConditionalHandler.NotMetAction notMetAction)
        {
            super(notMetAction);

            _expectedWhenNotApplied = switch (notMetAction)
            {
                case REJECT -> "404 Not Found";
                case FORBIDDEN -> "403 Forbidden";
                case SKIP_THIS, SKIP_NEXT -> "200 OK";
            };
        }

        public String getExpectedWhenNotApplied()
        {
            return _expectedWhenNotApplied;
        }
    }

    public static class TestSkipThisHandler extends TestConditionalHandler
    {
        TestSkipThisHandler()
        {
            super(NotMetAction.SKIP_THIS);
        }

        @Override
        public boolean doHandle(Request request, Response response, Callback callback) throws Exception
        {
            response.getHeaders().put("Test", "applied");
            return super.doHandle(request, response, callback);
        }
    }

    public static class TestNextHandler extends TestConditionalHandler
    {
        public TestNextHandler(ConditionalHandler.NotMetAction notMetAction)
        {
            super(notMetAction);
            setHandler(new Handler.Wrapper()
            {
                @Override
                public boolean handle(Request request, Response response, Callback callback) throws Exception
                {
                    response.getHeaders().put("Test", "applied");
                    return super.handle(request, response, callback);
                }
            });
        }
    }
}
