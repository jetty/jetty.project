//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.cdi.websocket.basicapp;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import java.io.File;
import java.net.URI;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.websocket.server.ServerContainer;

import org.eclipse.jetty.cdi.servlet.EmbeddedCdiHandler;
import org.eclipse.jetty.cdi.websocket.CheckSocket;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.log.JettyLogHandler;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.util.WSURI;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

@Ignore("TODO: Fix CDI integration")
public class BasicAppTest
{
    private static final Logger LOG = Log.getLogger(BasicAppTest.class);
    
    private static Server server;
    @SuppressWarnings("unused")
    private static URI serverHttpURI;
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

        File baseDir = MavenTestingUtils.getTestResourcesDir();

        context.setBaseResource(Resource.newResource(baseDir));
        context.setContextPath("/");
        server.setHandler(context);
        
        // Add some websockets
        ServerContainer container = WebSocketServerContainerInitializer.configureContext(context);
        container.addEndpoint(EchoSocket.class);

        server.start();

        serverHttpURI = server.getURI().resolve("/");
        serverWebsocketURI = WSURI.toWebsocket(serverHttpURI);
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
    public void testWebSocketEcho() throws Exception
    {
        WebSocketClient client = new WebSocketClient();
        try
        {
            client.start();
            CheckSocket socket = new CheckSocket();
            Future<Session> futureSession = client.connect(socket,serverWebsocketURI.resolve("/echo"));

            Session session = futureSession.get(5, TimeUnit.SECONDS);
            session.getRemote().sendString("Hello World");

            String response = socket.getTextMessages().poll(5, TimeUnit.SECONDS);
            assertThat("Message[0]",response,is("Hello World"));

            socket.close(StatusCode.NORMAL,"Test complete");
            socket.awaitClose(5,TimeUnit.SECONDS);
        }
        finally
        {
            client.stop();
        }
    }
}
