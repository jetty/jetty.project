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

package org.eclipse.jetty.ee10.websocket.jakarta.tests.server;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.OnMessage;
import jakarta.websocket.server.ServerContainer;
import jakarta.websocket.server.ServerEndpoint;
import jakarta.websocket.server.ServerEndpointConfig;
import org.eclipse.jetty.ee10.servlet.DefaultServlet;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.websocket.jakarta.tests.Fuzzer;
import org.eclipse.jetty.ee10.websocket.jakarta.tests.LocalServer;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class SessionTest
{
    @ServerEndpoint(value = "/info/")
    public static class SessionInfoSocket
    {
        @OnMessage
        public String onMessage(jakarta.websocket.Session session, String message)
        {
            if ("pathParams".equalsIgnoreCase(message))
            {
                StringBuilder ret = new StringBuilder();
                ret.append("pathParams");
                Map<String, String> pathParams = session.getPathParameters();
                if (pathParams == null)
                {
                    ret.append("=<null>");
                }
                else
                {
                    ret.append('[').append(pathParams.size()).append(']');
                    List<String> keys = new ArrayList<>(pathParams.keySet());
                    Collections.sort(keys);
                    for (String key : keys)
                    {
                        String value = pathParams.get(key);
                        ret.append(": '").append(key).append("'=").append(value);
                    }
                }
                return ret.toString();
            }

            if ("requestUri".equalsIgnoreCase(message))
            {
                StringBuilder ret = new StringBuilder();
                ret.append("requestUri=");
                URI uri = session.getRequestURI();
                if (uri == null)
                {
                    ret.append("=<null>");
                }
                else
                {
                    ret.append(uri.toASCIIString());
                }
                return ret.toString();
            }

            // simple echo
            return "echo:'" + message + "'";
        }
    }

    public static class SessionInfoEndpoint extends Endpoint implements MessageHandler.Whole<String>
    {
        private jakarta.websocket.Session session;

        @Override
        public void onOpen(jakarta.websocket.Session session, EndpointConfig config)
        {
            this.session = session;
            this.session.addMessageHandler(this);
        }

        @Override
        public void onMessage(String message)
        {
            try
            {
                if ("pathParams".equalsIgnoreCase(message))
                {
                    StringBuilder ret = new StringBuilder();
                    ret.append("pathParams");
                    Map<String, String> pathParams = session.getPathParameters();
                    if (pathParams == null)
                    {
                        ret.append("=<null>");
                    }
                    else
                    {
                        ret.append('[').append(pathParams.size()).append(']');
                        List<String> keys = new ArrayList<>(pathParams.keySet());
                        Collections.sort(keys);
                        for (String key : keys)
                        {
                            String value = pathParams.get(key);
                            ret.append(": '").append(key).append("'=").append(value);
                        }
                    }
                    session.getBasicRemote().sendText(ret.toString());
                    return;
                }

                if ("requestUri".equalsIgnoreCase(message))
                {
                    StringBuilder ret = new StringBuilder();
                    ret.append("requestUri=");
                    URI uri = session.getRequestURI();
                    if (uri == null)
                    {
                        ret.append("=<null>");
                    }
                    else
                    {
                        ret.append(uri.toASCIIString());
                    }
                    session.getBasicRemote().sendText(ret.toString());
                    return;
                }

                // simple echo
                session.getBasicRemote().sendText("echo:'" + message + "'");
            }
            catch (IOException e)
            {
                e.printStackTrace(System.err);
            }
        }
    }

    private static class Case
    {
        public final String description;
        public Consumer<ServletContextHandler> customizer;

        public Case(String description, Consumer<ServletContextHandler> customizer)
        {
            this.description = description;
            this.customizer = customizer;
        }

        @Override
        public String toString()
        {
            return this.description;
        }
    }

    private static class Cases extends ArrayList<Arguments>
    {
        public void addCase(String description, Consumer<ServletContextHandler> customizer)
        {
            this.add(Arguments.of(new Case(description, customizer)));
        }
    }

    public static Stream<Arguments> data()
    {
        Cases cases = new Cases();
        cases.addCase("Default ServletContextHandler", context ->
        {
        });
        cases.addCase("With DefaultServlet only", context -> context.addServlet(DefaultServlet.class, "/"));
        cases.addCase("With Servlet Mapped to '/*'", context -> context.addServlet(DefaultServlet.class, "/*"));
        cases.addCase("With Servlet Mapped to '/info/*'", context -> context.addServlet(DefaultServlet.class, "/info/*"));
        return cases.stream();
    }

    private LocalServer server;

    @AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
    }

    public void setup(final Case testcase) throws Exception
    {
        server = new LocalServer();
        server.start();
        testcase.customizer.accept(server.getServletContextHandler());
        ServerContainer container = server.getServerContainer();
        container.addEndpoint(SessionInfoSocket.class); // default behavior
        Class<?> endpointClass = SessionInfoSocket.class;
        container.addEndpoint(ServerEndpointConfig.Builder.create(endpointClass, "/info/{a}/").build());
        container.addEndpoint(ServerEndpointConfig.Builder.create(endpointClass, "/info/{a}/{b}/").build());
        container.addEndpoint(ServerEndpointConfig.Builder.create(endpointClass, "/info/{a}/{b}/{c}/").build());
        container.addEndpoint(ServerEndpointConfig.Builder.create(endpointClass, "/info/{a}/{b}/{c}/{d}/").build());

        endpointClass = SessionInfoEndpoint.class;
        container.addEndpoint(ServerEndpointConfig.Builder.create(endpointClass, "/einfo/").build());
        container.addEndpoint(ServerEndpointConfig.Builder.create(endpointClass, "/einfo/{a}/").build());
        container.addEndpoint(ServerEndpointConfig.Builder.create(endpointClass, "/einfo/{a}/{b}/").build());
        container.addEndpoint(ServerEndpointConfig.Builder.create(endpointClass, "/einfo/{a}/{b}/{c}/").build());
        container.addEndpoint(ServerEndpointConfig.Builder.create(endpointClass, "/einfo/{a}/{b}/{c}/{d}/").build());
    }

    private void assertResponse(String requestPath, String requestMessage,
                                String expectedResponse) throws Exception
    {
        List<Frame> send = new ArrayList<>();
        send.add(new Frame(OpCode.TEXT).setPayload(requestMessage));
        send.add(CloseStatus.toFrame(CloseStatus.NORMAL));

        List<Frame> expect = new ArrayList<>();
        expect.add(new Frame(OpCode.TEXT).setPayload(expectedResponse));
        expect.add(CloseStatus.toFrame(CloseStatus.NORMAL));

        try (Fuzzer session = server.newNetworkFuzzer(requestPath))
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("data")
    public void testPathParamsAnnotatedEmpty(Case testCase) throws Exception
    {
        setup(testCase);
        assertResponse("/info/", "pathParams",
            "pathParams[0]");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("data")
    public void testPathParamsAnnotatedSingle(Case testCase) throws Exception
    {
        setup(testCase);
        assertResponse("/info/apple/", "pathParams",
            "pathParams[1]: 'a'=apple");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("data")
    public void testPathParamsAnnotatedDouble(Case testCase) throws Exception
    {
        setup(testCase);
        assertResponse("/info/apple/pear/", "pathParams",
            "pathParams[2]: 'a'=apple: 'b'=pear");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("data")
    public void testPathParamsAnnotatedTriple(Case testCase) throws Exception
    {
        setup(testCase);
        assertResponse("/info/apple/pear/cherry/", "pathParams",
            "pathParams[3]: 'a'=apple: 'b'=pear: 'c'=cherry");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("data")
    public void testPathParamsEndpointEmpty(Case testCase) throws Exception
    {
        setup(testCase);
        assertResponse("/einfo/", "pathParams",
            "pathParams[0]");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("data")
    public void testPathParamsEndpointSingle(Case testCase) throws Exception
    {
        setup(testCase);
        assertResponse("/einfo/apple/", "pathParams",
            "pathParams[1]: 'a'=apple");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("data")
    public void testPathParamsEndpointDouble(Case testCase) throws Exception
    {
        setup(testCase);
        assertResponse("/einfo/apple/pear/", "pathParams",
            "pathParams[2]: 'a'=apple: 'b'=pear");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("data")
    public void testPathParamsEndpointTriple(Case testCase) throws Exception
    {
        setup(testCase);
        assertResponse("/einfo/apple/pear/cherry/", "pathParams",
            "pathParams[3]: 'a'=apple: 'b'=pear: 'c'=cherry");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("data")
    public void testRequestUriAnnotatedBasic(Case testCase) throws Exception
    {
        setup(testCase);
        assertResponse("/info/", "requestUri",
            "requestUri=" + server.getWsUri().toASCIIString() + "info/");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("data")
    public void testRequestUriAnnotatedWithPathParam(Case testCase) throws Exception
    {
        setup(testCase);
        assertResponse("/info/apple/banana/", "requestUri",
            "requestUri=" + server.getWsUri().toASCIIString() +
                "info/apple/banana/");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("data")
    public void testRequestUriAnnotatedWithPathParamWithQuery(Case testCase) throws Exception
    {
        setup(testCase);
        assertResponse("/info/apple/banana/?fruit=fresh&store=grandmasfarm",
            "requestUri",
            "requestUri=" + server.getWsUri().toASCIIString() +
                "info/apple/banana/?fruit=fresh&store=grandmasfarm");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("data")
    public void testRequestUriEndpointBasic(Case testCase) throws Exception
    {
        setup(testCase);
        assertResponse("/einfo/", "requestUri",
            "requestUri=" + server.getWsUri().toASCIIString() + "einfo/");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("data")
    public void testRequestUriEndpointWithPathParam(Case testCase) throws Exception
    {
        setup(testCase);
        assertResponse("/einfo/apple/banana/", "requestUri",
            "requestUri=" + server.getWsUri().toASCIIString() + "einfo/apple/banana/");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("data")
    public void testRequestUriEndpointWithPathParamWithQuery(Case testCase) throws Exception
    {
        setup(testCase);
        assertResponse("/einfo/apple/banana/?fruit=fresh&store=grandmasfarm",
            "requestUri",
            "requestUri=" + server.getWsUri().toASCIIString() +
                "einfo/apple/banana/?fruit=fresh&store=grandmasfarm");
    }
}
