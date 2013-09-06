//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

import static org.hamcrest.Matchers.*;

import java.net.URI;
import java.util.Queue;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class SessionTest
{
    private static WSServer server;
    private static URI serverUri;

    @BeforeClass
    public static void startServer() throws Exception
    {
        server = new WSServer(MavenTestingUtils.getTargetTestingDir(SessionTest.class.getSimpleName()),"app");
        server.copyWebInf("empty-web.xml");
        server.copyClass(SessionInfoSocket.class);
        server.copyClass(SessionAltConfig.class);
        server.start();
        serverUri = server.getServerBaseURI();

        WebAppContext webapp = server.createWebAppContext();
        server.deployWebapp(webapp);
    }

    @AfterClass
    public static void stopServer()
    {
        server.stop();
    }

    private void assertResponse(String requestPath, String requestMessage, String expectedResponse) throws Exception
    {
        WebSocketClient client = new WebSocketClient();
        try
        {
            client.start();
            JettyEchoSocket clientEcho = new JettyEchoSocket();
            Future<Session> future = client.connect(clientEcho,serverUri.resolve(requestPath));
            // wait for connect
            future.get(1,TimeUnit.SECONDS);
            clientEcho.sendMessage(requestMessage);
            Queue<String> msgs = clientEcho.awaitMessages(1);
            Assert.assertThat("Expected message",msgs.poll(),is(expectedResponse));
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
}
