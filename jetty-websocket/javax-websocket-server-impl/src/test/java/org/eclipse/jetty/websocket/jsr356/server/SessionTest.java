//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

import static org.hamcrest.Matchers.is;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class SessionTest
{
    private static class Case
    {
        public final String description;
        public final Consumer<WebAppContext> customizer;

        public Case(String desc, Consumer<WebAppContext> consumer)
        {
            this.description = desc;
            this.customizer = consumer;
        }

        @Override
        public String toString()
        {
            return description;
        }

        public void addTo(List<Case[]> data)
        {
            data.add(new Case[] { this } );
        }
    }

    @Parameters(name = "{0}")
    public static Collection<Case[]> data()
    {
        List<Case[]> cases = new ArrayList<>();

        new Case("no customization", (context) -> {
                // no customization here
        }).addTo(cases);

        new Case("with DefaultServlet only",
                (context) -> context.addServlet(DefaultServlet.class, "/")
        ).addTo(cases);


        new Case("with Servlet mapped to root-glob",
                (context) -> context.addServlet(DefaultServlet.class, "/*")
        ).addTo(cases);


        new Case("with Servlet mapped to info-glob",
                // this tests the overlap of websocket paths and servlet paths
                // the SessionInfoSocket below is also mapped to "/info/"
                (context) -> context.addServlet(DefaultServlet.class, "/info/*")
        ).addTo(cases);

        return cases;
    }

    private final Case testcase;
    private final static AtomicInteger ID = new AtomicInteger(0);
    private WSServer server;
    private URI serverUri;

    public SessionTest(Case testcase)
    {
        this.testcase = testcase;
    }

    @Before
    public void startServer() throws Exception
    {
        server = new WSServer(MavenTestingUtils.getTargetTestingDir(SessionTest.class.getSimpleName() + "-" + ID.incrementAndGet()),"app");
        server.copyWebInf("empty-web.xml");
        server.copyClass(SessionInfoSocket.class);
        server.copyClass(SessionAltConfig.class);
        server.start();
        serverUri = server.getServerBaseURI();

        WebAppContext webapp = server.createWebAppContext();
        testcase.customizer.accept(webapp);
        server.deployWebapp(webapp);
    }

    @After
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
            Future<Session> future = client.connect(clientEcho,serverUri.resolve(requestPath));
            Session session = future.get(1,TimeUnit.SECONDS);
            session.getRemote().sendString(requestMessage);
            String msg = clientEcho.messages.poll(5, TimeUnit.SECONDS);
            Assert.assertThat("Expected message",msg,is(expectedResponse));
        }
        finally
        {
            client.stop();
        }
    }

    @Test
    public void testPathParams_Annotated_Empty() throws Exception
    {
        assertResponse("info/","pathParams","pathParams[0]");
    }

    @Test
    public void testPathParams_Annotated_Single() throws Exception
    {
        assertResponse("info/apple/","pathParams","pathParams[1]: 'a'=apple");
    }

    @Test
    public void testPathParams_Annotated_Double() throws Exception
    {
        assertResponse("info/apple/pear/","pathParams","pathParams[2]: 'a'=apple: 'b'=pear");
    }

    @Test
    public void testPathParams_Annotated_Triple() throws Exception
    {
        assertResponse("info/apple/pear/cherry/","pathParams","pathParams[3]: 'a'=apple: 'b'=pear: 'c'=cherry");
    }

    @Test
    public void testPathParams_Endpoint_Empty() throws Exception
    {
        assertResponse("einfo/","pathParams","pathParams[0]");
    }

    @Test
    public void testPathParams_Endpoint_Single() throws Exception
    {
        assertResponse("einfo/apple/","pathParams","pathParams[1]: 'a'=apple");
    }

    @Test
    public void testPathParams_Endpoint_Double() throws Exception
    {
        assertResponse("einfo/apple/pear/","pathParams","pathParams[2]: 'a'=apple: 'b'=pear");
    }

    @Test
    public void testPathParams_Endpoint_Triple() throws Exception
    {
        assertResponse("einfo/apple/pear/cherry/","pathParams","pathParams[3]: 'a'=apple: 'b'=pear: 'c'=cherry");
    }

    @Test
    public void testRequestUri_Annotated_Basic() throws Exception
    {
        URI expectedUri = serverUri.resolve("info/");
        assertResponse("info/","requestUri","requestUri=" + expectedUri.toASCIIString());
    }

    @Test
    public void testRequestUri_Annotated_WithPathParam() throws Exception
    {
        URI expectedUri = serverUri.resolve("info/apple/banana/");
        assertResponse("info/apple/banana/","requestUri","requestUri=" + expectedUri.toASCIIString());
    }

    @Test
    public void testRequestUri_Annotated_WithPathParam_WithQuery() throws Exception
    {
        URI expectedUri = serverUri.resolve("info/apple/banana/?fruit=fresh&store=grandmasfarm");
        assertResponse("info/apple/banana/?fruit=fresh&store=grandmasfarm","requestUri","requestUri=" + expectedUri.toASCIIString());
    }

    @Test
    public void testRequestUri_Endpoint_Basic() throws Exception
    {
        URI expectedUri = serverUri.resolve("einfo/");
        assertResponse("einfo/","requestUri","requestUri=" + expectedUri.toASCIIString());
    }

    @Test
    public void testRequestUri_Endpoint_WithPathParam() throws Exception
    {
        URI expectedUri = serverUri.resolve("einfo/apple/banana/");
        assertResponse("einfo/apple/banana/","requestUri","requestUri=" + expectedUri.toASCIIString());
    }

    @Test
    public void testRequestUri_Endpoint_WithPathParam_WithQuery() throws Exception
    {
        URI expectedUri = serverUri.resolve("einfo/apple/banana/?fruit=fresh&store=grandmasfarm");
        assertResponse("einfo/apple/banana/?fruit=fresh&store=grandmasfarm","requestUri","requestUri=" + expectedUri.toASCIIString());
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
}
