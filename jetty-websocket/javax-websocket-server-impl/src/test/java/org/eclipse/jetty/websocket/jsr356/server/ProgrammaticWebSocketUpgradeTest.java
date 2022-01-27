//
//  ========================================================================
//  Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.websocket.DeploymentException;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.MessageHandler;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpointConfig;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.ajax.JSON;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ProgrammaticWebSocketUpgradeTest
{
    private static final Map<String, String> PATH_PARAMS = new HashMap<>();

    static
    {
        PATH_PARAMS.put("param1", "value1");
        PATH_PARAMS.put("param2", "value2");
    }

    private static final JSON JSON = new JSON();
    private Server server;
    private ServerConnector connector;
    private WebSocketClient client;

    @BeforeEach
    public void before() throws Exception
    {
        client = new WebSocketClient();
        server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);

        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.SESSIONS);
        contextHandler.setContextPath("/");
        contextHandler.addServlet(new ServletHolder(new CustomUpgradeServlet()), "/");
        server.setHandler(contextHandler);

        WebSocketServerContainerInitializer.configure(contextHandler, null);

        server.start();
        client.start();
    }

    @AfterEach
    public void stop() throws Exception
    {
        client.stop();
        server.stop();
    }

    @WebSocket
    public static class ClientSocket
    {
        org.eclipse.jetty.websocket.api.Session session;
        CountDownLatch openLatch = new CountDownLatch(1);
        CountDownLatch closeLatch = new CountDownLatch(1);
        LinkedBlockingQueue<String> textMessages = new LinkedBlockingQueue<>();
        int statusCode;
        String reason;

        @OnWebSocketConnect
        public void onOpen(org.eclipse.jetty.websocket.api.Session session)
        {
            this.session = session;
            openLatch.countDown();
        }

        @OnWebSocketMessage
        public void onMessage(String message)
        {
            textMessages.add(message);
        }

        @OnWebSocketClose
        public void onClose(int statusCode, String reason)
        {
            this.statusCode = statusCode;
            this.reason = reason;
            closeLatch.countDown();
        }
    }

    public static class EchoSocket extends Endpoint implements MessageHandler.Whole<String>
    {
        private Session session;

        @Override
        public void onOpen(Session session, EndpointConfig config)
        {
            this.session = session;
            session.addMessageHandler(this);
        }

        @Override
        public void onMessage(String s)
        {
            session.getAsyncRemote().sendText(s);
        }
    }

    public static class PathParamsEndpoint extends Endpoint
    {
        @Override
        public void onOpen(Session session, EndpointConfig config)
        {
            try
            {
                session.getBasicRemote().sendText(JSON.toJSON(session.getPathParameters()));
                session.close();
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        }
    }

    public static class CustomUpgradeServlet extends HttpServlet
    {
        private ServerContainer container;

        @Override
        public void init(ServletConfig config) throws ServletException
        {
            super.init(config);
            container = (ServerContainer)getServletContext().getAttribute("javax.websocket.server.ServerContainer");
        }

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            try
            {
                switch (request.getServletPath())
                {
                    case "/echo":
                    {
                        ServerEndpointConfig sec = ServerEndpointConfig.Builder.create(EchoSocket.class, "/").build();
                        HashMap<String, String> pathParams = new HashMap<>();
                        container.upgradeHttpToWebSocket(request, response, sec, pathParams);
                        break;
                    }
                    case "/pathParams":
                    {
                        ServerEndpointConfig sec = ServerEndpointConfig.Builder.create(PathParamsEndpoint.class, "/").build();
                        container.upgradeHttpToWebSocket(request, response, sec, PATH_PARAMS);
                        break;
                    }
                    default:
                        throw new IllegalStateException();
                }
            }
            catch (DeploymentException e)
            {
                throw new ServletException(e);
            }
        }
    }

    @Test
    public void testWebSocketUpgrade() throws Exception
    {
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/echo");
        ClientSocket socket = new ClientSocket();
        client.connect(socket, uri).get(5, TimeUnit.SECONDS);
        socket.session.getRemote().sendString("hello world");
        socket.session.close();
        assertTrue(socket.closeLatch.await(5, TimeUnit.SECONDS));

        String msg = socket.textMessages.poll();
        assertThat(msg, is("hello world"));
        assertThat(socket.statusCode, is(StatusCode.NORMAL));
    }

    @Test
    public void testPathParameters() throws Exception
    {
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/pathParams");
        ClientSocket socket = new ClientSocket();
        client.connect(socket, uri).get(5, TimeUnit.SECONDS);
        assertTrue(socket.closeLatch.await(5, TimeUnit.SECONDS));

        String msg = socket.textMessages.poll();
        assertThat(JSON.fromJSON(msg), is(PATH_PARAMS));
        assertThat(socket.statusCode, is(StatusCode.NORMAL));
    }
}
