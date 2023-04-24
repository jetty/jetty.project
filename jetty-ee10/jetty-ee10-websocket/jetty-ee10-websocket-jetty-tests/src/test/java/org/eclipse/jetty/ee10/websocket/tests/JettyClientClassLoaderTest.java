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

package org.eclipse.jetty.ee10.websocket.tests;

import java.net.URI;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.ee10.websocket.client.config.JettyWebSocketClientConfiguration;
import org.eclipse.jetty.ee10.websocket.server.JettyWebSocketServlet;
import org.eclipse.jetty.ee10.websocket.server.JettyWebSocketServletFactory;
import org.eclipse.jetty.ee10.websocket.server.config.JettyWebSocketConfiguration;
import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Configurable;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketOpen;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.client.CoreClientUpgradeRequest;
import org.eclipse.jetty.xml.XmlConfiguration;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnJre;
import org.junit.jupiter.api.condition.JRE;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class JettyClientClassLoaderTest
{
    private final WebAppTester webAppTester = new WebAppTester();
    private final HttpClient httpClient = new HttpClient();

    @FunctionalInterface
    interface ThrowingRunnable
    {
        void run() throws Exception;
    }

    public void start(ThrowingRunnable configuration) throws Exception
    {
        configuration.run();
        webAppTester.start();
        httpClient.start();
    }

    @AfterEach
    public void after() throws Exception
    {
        httpClient.stop();
        webAppTester.stop();
    }

    @WebSocket
    public static class ClientSocket
    {
        LinkedBlockingQueue<String> textMessages = new LinkedBlockingQueue<>();

        @OnWebSocketOpen
        public void onOpen(Session session) throws Exception
        {
            session.sendText("ContextClassLoader: " + Thread.currentThread().getContextClassLoader(), Callback.NOOP);
        }

        @OnWebSocketMessage
        public void onMessage(String message)
        {
            textMessages.add(message);
        }
    }

    @WebServlet("/servlet")
    public static class WebSocketClientServlet extends HttpServlet
    {
        private final WebSocketClient clientContainer = new WebSocketClient();

        @Override
        public void init(ServletConfig config) throws ServletException
        {
            try
            {
                clientContainer.start();
            }
            catch (Exception e)
            {
                throw new ServletException(e);
            }
        }

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
        {
            URI wsEchoUri = URI.create("ws://localhost:" + req.getServerPort() + "/echo");
            ClientSocket clientSocket = new ClientSocket();

            try (Session ignored = clientContainer.connect(clientSocket, wsEchoUri).get(5, TimeUnit.SECONDS))
            {
                String recv = clientSocket.textMessages.poll(5, TimeUnit.SECONDS);
                assertNotNull(recv);
                resp.setStatus(HttpStatus.OK_200);
                resp.getWriter().println(recv);
                resp.getWriter().println("ClientClassLoader: " + clientContainer.getClass().getClassLoader());
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
        }
    }

    @WebServlet("/")
    public static class EchoServlet extends JettyWebSocketServlet
    {
        @Override
        protected void configure(JettyWebSocketServletFactory factory)
        {
            factory.register(EchoSocket.class);
        }
    }

    @WebSocket
    public static class EchoSocket
    {
        @OnWebSocketMessage
        public void onMessage(Session session, String message) throws Exception
        {
            session.sendText(message, Callback.NOOP);
        }
    }

    public WebAppTester.WebApp createWebSocketWebapp(String contextName) throws Exception
    {
        WebAppTester.WebApp app = webAppTester.createWebApp(contextName);

        // Copy over the individual jars required for Javax WebSocket.
        app.createWebInf();
        app.copyLib(Configurable.class, "jetty-websocket-jetty-api.jar");
        app.copyLib(WebSocketClient.class, "jetty-websocket-jetty-client.jar");
        app.copyLib(WebSocketSession.class, "jetty-websocket-jetty-common.jar");
        app.copyLib(ContainerLifeCycle.class, "jetty-util.jar");
        app.copyLib(CoreClientUpgradeRequest.class, "jetty-websocket-core-client.jar");
        app.copyLib(WebSocketComponents.class, "jetty-websocket-core-common.jar");
        app.copyLib(Response.class, "jetty-client.jar");
        app.copyLib(EndPoint.class, "jetty-io.jar");
        app.copyLib(BadMessageException.class, "jetty-http.jar");
        app.copyLib(XmlConfiguration.class, "jetty-xml.jar");

        return app;
    }

    @Test
    public void websocketProvidedByServer() throws Exception
    {
        start(() ->
        {
            WebAppTester.WebApp app1 = webAppTester.createWebApp("/app");
            app1.addConfiguration(new JettyWebSocketClientConfiguration());
            app1.createWebInf();
            app1.copyClass(WebSocketClientServlet.class);
            app1.copyClass(ClientSocket.class);
            app1.deploy();

            WebAppTester.WebApp app2 = webAppTester.createWebApp("/echo");
            app2.addConfiguration(new JettyWebSocketConfiguration());
            app2.createWebInf();
            app2.copyClass(EchoServlet.class);
            app2.copyClass(EchoSocket.class);
            app2.deploy();
        });

        // After hitting each WebApp we will get 200 response if test succeeds.
        ContentResponse response = httpClient.GET(webAppTester.getServerUri().resolve("/app/servlet"));
        MatcherAssert.assertThat(response.getStatus(), Matchers.is(HttpStatus.OK_200));

        // The ContextClassLoader in the WebSocketClients onOpen was the WebAppClassloader.
        MatcherAssert.assertThat(response.getContentAsString(), containsString("ContextClassLoader: WebAppClassLoader"));

        // Verify that we used Servers version of WebSocketClient.
        ClassLoader serverClassLoader = webAppTester.getServer().getClass().getClassLoader();
        MatcherAssert.assertThat(response.getContentAsString(), containsString("ClientClassLoader: " + serverClassLoader));
    }

    /**
     * This reproduces some classloading issue with MethodHandles in JDK14-110, This has been fixed in JDK16.
     * @see <a href="https://bugs.openjdk.java.net/browse/JDK-8244090">JDK-8244090</a>
     */
    @DisabledOnJre({JRE.JAVA_14, JRE.JAVA_15})
    @Test
    public void websocketProvidedByWebApp() throws Exception
    {
        start(() ->
        {
            WebAppTester.WebApp app1 = createWebSocketWebapp("/app");
            app1.createWebInf();
            app1.copyClass(WebSocketClientServlet.class);
            app1.copyClass(ClientSocket.class);
            app1.deploy();

            WebAppTester.WebApp app2 = webAppTester.createWebApp("/echo");
            app2.addConfiguration(new JettyWebSocketConfiguration());
            app2.createWebInf();
            app2.copyClass(EchoServlet.class);
            app2.copyClass(EchoSocket.class);
            app2.deploy();
        });

        // After hitting each WebApp we will get 200 response if test succeeds.
        ContentResponse response = httpClient.GET(webAppTester.getServerUri().resolve("/app/servlet"));
        MatcherAssert.assertThat(response.getStatus(), Matchers.is(HttpStatus.OK_200));

        // The ContextClassLoader in the WebSocketClients onOpen was the WebAppClassloader.
        MatcherAssert.assertThat(response.getContentAsString(), containsString("ContextClassLoader: WebAppClassLoader"));

        // Verify that we used WebApps version of WebSocketClient.
        MatcherAssert.assertThat(response.getContentAsString(), containsString("ClientClassLoader: WebAppClassLoader"));
    }
}
