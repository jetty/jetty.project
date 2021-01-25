//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.jsr356.server;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class SessionTest
{
    public static Stream<Arguments> scenarios()
    {
        List<Scenario> cases = new ArrayList<>();

        cases.add(new Scenario("no customization", (context) ->
        {
            // no customization here
        }));

        cases.add(new Scenario("with DefaultServlet only",
            (context) -> context.addServlet(DefaultServlet.class, "/")
        ));

        cases.add(new Scenario("with Servlet mapped to root-glob",
            (context) -> context.addServlet(DefaultServlet.class, "/*")
        ));

        cases.add(new Scenario("with Servlet mapped to info-glob",
            // this tests the overlap of websocket paths and servlet paths
            // the SessionInfoSocket below is also mapped to "/info/"
            (context) -> context.addServlet(DefaultServlet.class, "/info/*")
        ));

        return cases.stream().map(Arguments::of);
    }

    private static final AtomicInteger ID = new AtomicInteger(0);
    private WSServer server;
    private URI serverUri;

    public void startServer(Scenario scenario) throws Exception
    {
        server = new WSServer(MavenTestingUtils.getTargetTestingDir(SessionTest.class.getSimpleName() + "-" + ID.incrementAndGet()), "app");
        server.copyWebInf("empty-web.xml");
        server.copyClass(SessionInfoSocket.class);
        server.copyClass(SessionAltConfig.class);
        server.start();
        serverUri = server.getServerBaseURI();

        WebAppContext webapp = server.createWebAppContext();
        scenario.customizer.accept(webapp);
        server.deployWebapp(webapp);
    }

    @AfterEach
    public void stopServer()
    {
        server.stop();
    }

    private void assertResponse(String requestPath, String requestMessage, String expectedResponse) throws Exception
    {
        WebSocketClient client = new WebSocketClient();
        try
        {
            client.start();
            ClientEchoSocket clientEcho = new ClientEchoSocket();
            Future<Session> future = client.connect(clientEcho, serverUri.resolve(requestPath));
            Session session = future.get(1, TimeUnit.SECONDS);
            session.getRemote().sendString(requestMessage);
            String msg = clientEcho.messages.poll(5, TimeUnit.SECONDS);
            assertThat("Expected message", msg, is(expectedResponse));
        }
        finally
        {
            client.stop();
        }
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testPathParamsAnnotatedEmpty(Scenario scenario) throws Exception
    {
        startServer(scenario);
        assertResponse("info/", "pathParams", "pathParams[0]");
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testPathParamsAnnotatedSingle(Scenario scenario) throws Exception
    {
        startServer(scenario);
        assertResponse("info/apple/", "pathParams", "pathParams[1]: 'a'=apple");
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testPathParamsAnnotatedDouble(Scenario scenario) throws Exception
    {
        startServer(scenario);
        assertResponse("info/apple/pear/", "pathParams", "pathParams[2]: 'a'=apple: 'b'=pear");
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testPathParamsAnnotatedTriple(Scenario scenario) throws Exception
    {
        startServer(scenario);
        assertResponse("info/apple/pear/cherry/", "pathParams", "pathParams[3]: 'a'=apple: 'b'=pear: 'c'=cherry");
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testPathParamsEndpointEmpty(Scenario scenario) throws Exception
    {
        startServer(scenario);
        assertResponse("einfo/", "pathParams", "pathParams[0]");
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testPathParamsEndpointSingle(Scenario scenario) throws Exception
    {
        startServer(scenario);
        assertResponse("einfo/apple/", "pathParams", "pathParams[1]: 'a'=apple");
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testPathParamsEndpointDouble(Scenario scenario) throws Exception
    {
        startServer(scenario);
        assertResponse("einfo/apple/pear/", "pathParams", "pathParams[2]: 'a'=apple: 'b'=pear");
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testPathParamsEndpointTriple(Scenario scenario) throws Exception
    {
        startServer(scenario);
        assertResponse("einfo/apple/pear/cherry/", "pathParams", "pathParams[3]: 'a'=apple: 'b'=pear: 'c'=cherry");
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testRequestUriAnnotatedBasic(Scenario scenario) throws Exception
    {
        startServer(scenario);
        URI expectedUri = serverUri.resolve("info/");
        assertResponse("info/", "requestUri", "requestUri=" + expectedUri.toASCIIString());
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testRequestUriAnnotatedWithPathParam(Scenario scenario) throws Exception
    {
        startServer(scenario);
        URI expectedUri = serverUri.resolve("info/apple/banana/");
        assertResponse("info/apple/banana/", "requestUri", "requestUri=" + expectedUri.toASCIIString());
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testRequestUriAnnotatedWithPathParamWithQuery(Scenario scenario) throws Exception
    {
        startServer(scenario);
        URI expectedUri = serverUri.resolve("info/apple/banana/?fruit=fresh&store=grandmasfarm");
        assertResponse("info/apple/banana/?fruit=fresh&store=grandmasfarm", "requestUri", "requestUri=" + expectedUri.toASCIIString());
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testRequestUriEndpointBasic(Scenario scenario) throws Exception
    {
        startServer(scenario);
        URI expectedUri = serverUri.resolve("einfo/");
        assertResponse("einfo/", "requestUri", "requestUri=" + expectedUri.toASCIIString());
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testRequestUriEndpointWithPathParam(Scenario scenario) throws Exception
    {
        startServer(scenario);
        URI expectedUri = serverUri.resolve("einfo/apple/banana/");
        assertResponse("einfo/apple/banana/", "requestUri", "requestUri=" + expectedUri.toASCIIString());
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testRequestUriEndpointWithPathParamWithQuery(Scenario scenario) throws Exception
    {
        startServer(scenario);
        URI expectedUri = serverUri.resolve("einfo/apple/banana/?fruit=fresh&store=grandmasfarm");
        assertResponse("einfo/apple/banana/?fruit=fresh&store=grandmasfarm", "requestUri", "requestUri=" + expectedUri.toASCIIString());
    }

    @WebSocket
    public static class ClientEchoSocket
    {
        public LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();

        @OnWebSocketMessage
        public void onText(String msg)
        {
            messages.offer(msg);
        }
    }

    private static class Scenario
    {
        public final String description;
        public final Consumer<WebAppContext> customizer;

        public Scenario(String desc, Consumer<WebAppContext> consumer)
        {
            this.description = desc;
            this.customizer = consumer;
        }

        @Override
        public String toString()
        {
            return description;
        }
    }
}
