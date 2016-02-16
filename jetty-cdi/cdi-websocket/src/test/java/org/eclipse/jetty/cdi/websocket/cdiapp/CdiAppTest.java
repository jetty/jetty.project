//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.cdi.websocket.cdiapp;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import java.io.File;
import java.net.URI;
import java.util.concurrent.TimeUnit;

import javax.websocket.server.ServerContainer;

import org.eclipse.jetty.cdi.servlet.EmbeddedCdiHandler;
import org.eclipse.jetty.cdi.websocket.CheckSocket;
import org.eclipse.jetty.cdi.websocket.WebSocketCdiInitializer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.log.JettyLogHandler;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class CdiAppTest
{
    private static final Logger LOG = Log.getLogger(CdiAppTest.class);
    private static Server server;
    private static URI serverWebsocketURI;

    @BeforeClass
    public static void startServer() throws Exception
    {
        JettyLogHandler.config();

        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        EmbeddedCdiHandler context = new EmbeddedCdiHandler();
        WebSocketCdiInitializer.configureContext(context);

        File baseDir = MavenTestingUtils.getTestResourcesDir();

        context.setBaseResource(Resource.newResource(baseDir));
        context.setContextPath("/");
        server.setHandler(context);
        
        // Add some websockets
        ServerContainer container = WebSocketServerContainerInitializer.configureContext(context);
        container.addEndpoint(EchoSocket.class);
        container.addEndpoint(InfoSocket.class);

        server.start();

        String host = connector.getHost();
        if (host == null)
        {
            host = "localhost";
        }
        int port = connector.getLocalPort();
        serverWebsocketURI = new URI(String.format("ws://%s:%d/",host,port));
    }

    @AfterClass
    public static void stopServer()
    {
        try
        {
            server.stop();
        }
        catch (Exception e)
        {
            LOG.warn(e);
        }
    }

    @Test
    public void testWebSocketActivated() throws Exception
    {
        WebSocketClient client = new WebSocketClient();
        try
        {
            client.start();
            CheckSocket socket = new CheckSocket();
            client.connect(socket,serverWebsocketURI.resolve("/echo"));

            socket.awaitOpen(2,TimeUnit.SECONDS);
            socket.sendText("Hello");
            socket.close(StatusCode.NORMAL,"Test complete");
            socket.awaitClose(2,TimeUnit.SECONDS);

            assertThat("Messages received",socket.getTextMessages().size(),is(1));
            assertThat("Message[0]",socket.getTextMessages().poll(),is("Hello"));
        }
        finally
        {
            client.stop();
        }
    }

    @Test
    public void testWebSocket_Info_FieldPresence() throws Exception
    {
        WebSocketClient client = new WebSocketClient();
        try
        {
            client.start();
            CheckSocket socket = new CheckSocket();
            client.connect(socket,serverWebsocketURI.resolve("/cdi-info"));

            socket.awaitOpen(2,TimeUnit.SECONDS);
            socket.sendText("info");
            socket.close(StatusCode.NORMAL,"Test complete");
            socket.awaitClose(2,TimeUnit.SECONDS);

            assertThat("Messages received",socket.getTextMessages().size(),is(1));
            String response = socket.getTextMessages().poll();
            System.err.println(response);

            assertThat("Message[0]",response,
                    allOf(
                            containsString("websocketSession is PRESENT"),
                            containsString("httpSession is PRESENT"),
                            containsString("servletContext is PRESENT")
                    ));
        }
        finally
        {
            client.stop();
        }
    }
    
    @Test
    public void testWebSocket_Info_DataFromCdi() throws Exception
    {
        WebSocketClient client = new WebSocketClient();
        try
        {
            client.start();
            CheckSocket socket = new CheckSocket();
            client.connect(socket,serverWebsocketURI.resolve("/cdi-info"));

            socket.awaitOpen(2,TimeUnit.SECONDS);
            socket.sendText("data|stuff");
            socket.close(StatusCode.NORMAL,"Test complete");
            socket.awaitClose(2,TimeUnit.SECONDS);

            assertThat("Messages received",socket.getTextMessages().size(),is(2));
            String response = socket.getTextMessages().poll();
            System.out.println("[0]" + response);
            assertThat("Message[0]",response,containsString("Hello there stuff"));
            System.out.println("[1]" + socket.getTextMessages().poll());
        }
        finally
        {
            client.stop();
        }
    }
}
