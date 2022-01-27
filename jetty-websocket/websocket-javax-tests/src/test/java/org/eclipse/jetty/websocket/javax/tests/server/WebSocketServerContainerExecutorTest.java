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

package org.eclipse.jetty.websocket.javax.tests.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executor;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.websocket.ClientEndpoint;
import javax.websocket.ContainerProvider;
import javax.websocket.EndpointConfig;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.javax.server.config.JavaxWebSocketServletContainerInitializer;
import org.eclipse.jetty.websocket.javax.server.internal.JavaxWebSocketServerContainer;
import org.eclipse.jetty.websocket.javax.tests.WSURI;
import org.junit.jupiter.api.Test;

import static org.eclipse.jetty.websocket.javax.server.config.JavaxWebSocketServletContainerInitializer.HTTPCLIENT_ATTRIBUTE;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.Matchers.startsWith;

public class WebSocketServerContainerExecutorTest
{
    @ServerEndpoint("/echo")
    public static class EchoSocket
    {
        @OnMessage
        public String echo(String msg)
        {
            return msg;
        }

        @OnError
        public void onError(Throwable cause)
        {
            // ignore
        }
    }

    @ClientEndpoint
    public static class EndpointAdapter
    {
        @OnOpen
        public void onOpen(Session session, EndpointConfig config)
        {
            /* do nothing */
        }
    }

    /**
     * Using the Client specific techniques of JSR356, connect to the echo socket
     * and perform an echo request.
     */
    public static class ClientConnectServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            // Client specific technique
            WebSocketContainer container = ContainerProvider.getWebSocketContainer();
            try
            {
                URI wsURI = WSURI.toWebsocket(req.getRequestURL()).resolve("/echo");
                Session session = container.connectToServer(new EndpointAdapter(), wsURI);
                // don't care about the data sent, just the connect itself.
                session.getBasicRemote().sendText("Hello");
                session.close();
                resp.setContentType("text/plain");
                resp.getWriter().println("Connected to " + wsURI);
            }
            catch (Throwable t)
            {
                throw new ServletException(t);
            }
        }
    }

    /**
     * Using the Server specific techniques of JSR356, connect to the echo socket
     * and perform an echo request.
     */
    public static class ServerConnectServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            // Server specific technique
            javax.websocket.server.ServerContainer container =
                (javax.websocket.server.ServerContainer)
                    req.getServletContext().getAttribute("javax.websocket.server.ServerContainer");
            try
            {
                URI wsURI = WSURI.toWebsocket(req.getRequestURL()).resolve("/echo");
                Session session = container.connectToServer(new EndpointAdapter(), wsURI);
                // don't care about the data sent, just the connect itself.
                session.getBasicRemote().sendText("Hello");
                session.close();
                resp.setContentType("text/plain");
                resp.getWriter().println("Connected to " + wsURI);
            }
            catch (Throwable t)
            {
                throw new ServletException(t);
            }
        }
    }

    private Executor getJavaxServerContainerExecutor(ServletContextHandler servletContextHandler)
    {
        JavaxWebSocketServerContainer serverContainer = JavaxWebSocketServerContainer.getContainer(
            servletContextHandler.getServletContext());
        return serverContainer.getExecutor();
    }

    @Test
    public void testClientExecutor() throws Exception
    {
        Server server = new Server(0);
        ServletContextHandler contextHandler = new ServletContextHandler();
        server.setHandler(contextHandler);

        //Executor to use
        Executor executor = new QueuedThreadPool();

        //set httpClient on server
        HttpClient httpClient = new HttpClient();
        httpClient.setName("Javax-WebSocketServer@" + Integer.toHexString(httpClient.hashCode()));
        httpClient.setExecutor(executor);
        server.addBean(httpClient, true);
        server.setAttribute(HTTPCLIENT_ATTRIBUTE, httpClient);

        // Using JSR356 Server Techniques to connectToServer()
        contextHandler.addServlet(ServerConnectServlet.class, "/connect");
        JavaxWebSocketServletContainerInitializer.configure(contextHandler, (context, container) ->
            container.addEndpoint(EchoSocket.class));

        try
        {
            server.start();
            String response = doGet(server.getURI().resolve("/connect"));
            assertThat("Response", response, startsWith("Connected to ws://"));

            Executor containerExecutor = getJavaxServerContainerExecutor(contextHandler);
            assertThat(containerExecutor, sameInstance(executor));
        }
        finally
        {
            server.stop();
        }
    }

    @Test
    public void testServerExecutor() throws Exception
    {
        Server server = new Server(0);
        ServletContextHandler contextHandler = new ServletContextHandler();
        server.setHandler(contextHandler);

        //Executor to use
        Executor executor = server.getThreadPool();

        // Using JSR356 Server Techniques to connectToServer()
        contextHandler.addServlet(ServerConnectServlet.class, "/connect");
        JavaxWebSocketServletContainerInitializer.configure(contextHandler, (context, container) ->
            container.addEndpoint(EchoSocket.class));
        try
        {
            server.start();
            String response = doGet(server.getURI().resolve("/connect"));
            assertThat("Response", response, startsWith("Connected to ws://"));

            Executor containerExecutor = getJavaxServerContainerExecutor(contextHandler);
            assertThat(containerExecutor, sameInstance(executor));
        }
        finally
        {
            server.stop();
        }
    }

    private String doGet(URI destURI) throws IOException
    {
        HttpURLConnection http = (HttpURLConnection)destURI.toURL().openConnection();
        assertThat("HTTP GET (" + destURI + ") Response Code", http.getResponseCode(), is(200));
        try (InputStream in = http.getInputStream();
             InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
             StringWriter writer = new StringWriter())
        {
            IO.copy(reader, writer);
            return writer.toString();
        }
    }
}
