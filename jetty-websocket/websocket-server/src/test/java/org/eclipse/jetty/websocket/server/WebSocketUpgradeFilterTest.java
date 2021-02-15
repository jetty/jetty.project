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

package org.eclipse.jetty.websocket.server;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import javax.servlet.DispatcherType;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.common.test.BlockheadClient;
import org.eclipse.jetty.websocket.common.test.BlockheadClientRequest;
import org.eclipse.jetty.websocket.common.test.BlockheadConnection;
import org.eclipse.jetty.websocket.common.test.Timeouts;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;

public class WebSocketUpgradeFilterTest
{
    interface ServerProvider
    {
        Server newServer() throws Exception;
    }

    private static BlockheadClient client;

    @BeforeAll
    public static void startClient() throws Exception
    {
        client = new BlockheadClient();
        client.setIdleTimeout(TimeUnit.SECONDS.toMillis(2));
        client.start();
    }

    @AfterAll
    public static void stopClient() throws Exception
    {
        client.stop();
    }

    private static AtomicInteger uniqTestDirId = new AtomicInteger(0);

    private static File getNewTestDir()
    {
        return MavenTestingUtils.getTargetTestingDir("WSUF-webxml-" + uniqTestDirId.getAndIncrement());
    }

    public static Stream<Arguments> scenarios()
    {
        final WebSocketCreator infoCreator = (req, resp) -> new InfoSocket();

        List<Arguments> cases = new ArrayList<>();

        // Embedded WSUF.configureContext(), directly app-ws configuration

        cases.add(Arguments.of("wsuf.configureContext/Direct configure", (ServerProvider)() ->
        {
            Server server1 = new Server();
            ServerConnector connector = new ServerConnector(server1);
            connector.setPort(0);
            server1.addConnector(connector);

            ServletContextHandler context = new ServletContextHandler();
            context.setContextPath("/");
            server1.setHandler(context);

            WebSocketUpgradeFilter wsuf = WebSocketUpgradeFilter.configureContext(context);

            // direct configuration via WSUF
            wsuf.getFactory().getPolicy().setMaxTextMessageSize(10 * 1024 * 1024);
            wsuf.addMapping("/info/*", infoCreator);

            server1.start();
            return server1;
        }));

        // Embedded WSUF.configureContext(), apply app-ws configuration via attribute

        cases.add(Arguments.of("wsuf.configureContext/Attribute based configure", (ServerProvider)() ->
        {
            Server server12 = new Server();
            ServerConnector connector = new ServerConnector(server12);
            connector.setPort(0);
            server12.addConnector(connector);

            ServletContextHandler context = new ServletContextHandler();
            context.setContextPath("/");
            server12.setHandler(context);

            WebSocketUpgradeFilter.configureContext(context);

            // configuration via attribute
            NativeWebSocketConfiguration configuration = (NativeWebSocketConfiguration)context.getServletContext().getAttribute(NativeWebSocketConfiguration.class.getName());
            assertThat("NativeWebSocketConfiguration", configuration, notNullValue());
            configuration.getFactory().getPolicy().setMaxTextMessageSize(10 * 1024 * 1024);
            configuration.addMapping("/info/*", infoCreator);

            server12.start();

            return server12;
        }));

        // Embedded WSUF, added as filter, apply app-ws configuration via attribute

        cases.add(Arguments.of("wsuf/addFilter/Attribute based configure", (ServerProvider)() ->
        {
            Server server13 = new Server();
            ServerConnector connector = new ServerConnector(server13);
            connector.setPort(0);
            server13.addConnector(connector);

            ServletContextHandler context = new ServletContextHandler();
            context.setContextPath("/");
            server13.setHandler(context);
            context.addFilter(WebSocketUpgradeFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));

            NativeWebSocketConfiguration configuration = new NativeWebSocketConfiguration(context.getServletContext());
            configuration.getFactory().getPolicy().setMaxTextMessageSize(10 * 1024 * 1024);
            configuration.addMapping("/info/*", infoCreator);
            context.setAttribute(NativeWebSocketConfiguration.class.getName(), configuration);

            server13.start();

            return server13;
        }));

        // Embedded WSUF, added as filter, apply app-ws configuration via wsuf constructor

        cases.add(Arguments.of("wsuf/addFilter/WSUF Constructor configure", new ServerProvider()
        {
            @Override
            public Server newServer() throws Exception
            {
                Server server = new Server();
                ServerConnector connector = new ServerConnector(server);
                connector.setPort(0);
                server.addConnector(connector);

                ServletContextHandler context = new ServletContextHandler();
                context.setContextPath("/");
                server.setHandler(context);

                NativeWebSocketConfiguration configuration = new NativeWebSocketConfiguration(context.getServletContext());
                configuration.getFactory().getPolicy().setMaxTextMessageSize(10 * 1024 * 1024);
                configuration.addMapping("/info/*", infoCreator);
                context.addBean(configuration, true);

                FilterHolder wsufHolder = new FilterHolder(new WebSocketUpgradeFilter(configuration));
                context.addFilter(wsufHolder, "/*", EnumSet.of(DispatcherType.REQUEST));

                server.start();

                return server;
            }
        }));

        // Embedded WSUF, added as filter, apply app-ws configuration via ServletContextListener

        cases.add(Arguments.of("wsuf.configureContext/ServletContextListener configure", (ServerProvider)() ->
        {
            Server server14 = new Server();
            ServerConnector connector = new ServerConnector(server14);
            connector.setPort(0);
            server14.addConnector(connector);

            ServletContextHandler context = new ServletContextHandler();
            context.setContextPath("/");
            server14.setHandler(context);
            context.addFilter(WebSocketUpgradeFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));
            context.addEventListener(new InfoContextListener());

            server14.start();

            return server14;
        }));

        // WSUF from web.xml, SCI active, apply app-ws configuration via ServletContextListener

        cases.add(Arguments.of("wsuf/WebAppContext/web.xml/ServletContextListener", (ServerProvider)() ->
        {
            File testDir = getNewTestDir();

            WSServer server15 = new WSServer(testDir, "/");

            server15.copyWebInf("wsuf-config-via-listener.xml");
            server15.copyClass(InfoSocket.class);
            server15.copyClass(InfoContextAttributeListener.class);
            server15.start();

            WebAppContext webapp = server15.createWebAppContext();
            server15.deployWebapp(webapp);

            return server15.getServer();
        }));

        // WSUF from web.xml, SCI active, apply app-ws configuration via ServletContextListener with WEB-INF/lib/jetty-http.jar

        cases.add(Arguments.of("wsuf/WebAppContext/web.xml/ServletContextListener/jetty-http.jar", new ServerProvider()
        {
            @Override
            public Server newServer() throws Exception
            {
                File testDir = getNewTestDir();

                WSServer server = new WSServer(testDir, "/");

                server.copyWebInf("wsuf-config-via-listener.xml");
                server.copyClass(InfoSocket.class);
                server.copyClass(InfoContextAttributeListener.class);
                // Add a jetty-http.jar to ensure that the classloader constraints
                // and the WebAppClassloader setup is sane and correct
                // The odd version string is present to capture bad regex behavior in Jetty
                server.copyLib(org.eclipse.jetty.http.pathmap.PathSpec.class, "jetty-http-9.99.999.jar");
                server.start();

                WebAppContext webapp = server.createWebAppContext();
                server.deployWebapp(webapp);

                return server.getServer();
            }
        }));

        // WSUF from web.xml, SCI active, apply app-ws configuration via Servlet.init

        cases.add(Arguments.of("wsuf/WebAppContext/web.xml/Servlet.init", (ServerProvider)() ->
        {
            File testDir = getNewTestDir();

            WSServer server16 = new WSServer(testDir, "/");

            server16.copyWebInf("wsuf-config-via-servlet-init.xml");
            server16.copyClass(InfoSocket.class);
            server16.copyClass(InfoServlet.class);
            server16.start();

            WebAppContext webapp = server16.createWebAppContext();
            server16.deployWebapp(webapp);

            return server16.getServer();
        }));

        // xml based, wsuf, on alternate url-pattern and config attribute location

        cases.add(Arguments.of("wsuf/WebAppContext/web.xml/ServletContextListener/alt-config", (ServerProvider)() ->
        {
            File testDir = getNewTestDir();

            WSServer server17 = new WSServer(testDir, "/");

            server17.copyWebInf("wsuf-alt-config-via-listener.xml");
            server17.copyClass(InfoSocket.class);
            server17.copyClass(InfoContextAltAttributeListener.class);
            server17.start();

            WebAppContext webapp = server17.createWebAppContext();
            server17.deployWebapp(webapp);

            return server17.getServer();
        }));

        return cases.stream();
    }

    private Server server;
    private URI serverUri;

    private void startServer(ServerProvider serverProvider) throws Exception
    {
        this.server = serverProvider.newServer();

        ServerConnector connector = (ServerConnector)server.getConnectors()[0];

        // Establish the Server URI
        String host = connector.getHost();
        if (host == null)
        {
            host = "localhost";
        }
        int port = connector.getLocalPort();

        serverUri = new URI(String.format("ws://%s:%d/", host, port));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("scenarios")
    public void testNormalConfiguration(String testId, ServerProvider serverProvider) throws Exception
    {
        startServer(serverProvider);
        URI destUri = serverUri.resolve("/info/");

        BlockheadClientRequest request = client.newWsRequest(destUri);

        Future<BlockheadConnection> connFut = request.sendAsync();

        try (BlockheadConnection clientConn = connFut.get(Timeouts.CONNECT, Timeouts.CONNECT_UNIT))
        {
            clientConn.write(new TextFrame().setPayload("hello"));

            LinkedBlockingQueue<WebSocketFrame> frames = clientConn.getFrameQueue();
            WebSocketFrame received = frames.poll(Timeouts.POLL_EVENT, Timeouts.POLL_EVENT_UNIT);
            String payload = received.getPayloadAsUTF8();

            // If we can connect and send a text message, we know that the endpoint was
            // added properly, and the response will help us verify the policy configuration too
            assertThat("payload", payload, containsString("session.maxTextMessageSize=" + (10 * 1024 * 1024)));
        }
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("scenarios")
    public void testStopStartOfHandler(String testId, ServerProvider serverProvider) throws Exception
    {
        startServer(serverProvider);
        URI destUri = serverUri.resolve("/info/");

        BlockheadClientRequest request = client.newWsRequest(destUri);

        Future<BlockheadConnection> connFut = request.sendAsync();

        try (BlockheadConnection clientConn = connFut.get(Timeouts.CONNECT, Timeouts.CONNECT_UNIT))
        {
            clientConn.write(new TextFrame().setPayload("hello 1"));

            LinkedBlockingQueue<WebSocketFrame> frames = clientConn.getFrameQueue();
            WebSocketFrame received = frames.poll(Timeouts.POLL_EVENT, Timeouts.POLL_EVENT_UNIT);
            String payload = received.getPayloadAsUTF8();

            // If we can connect and send a text message, we know that the endpoint was
            // added properly, and the response will help us verify the policy configuration too
            assertThat("payload", payload, containsString("session.maxTextMessageSize=" + (10 * 1024 * 1024)));
        }

        server.getHandler().stop();
        server.getHandler().start();

        request = client.newWsRequest(destUri);

        connFut = request.sendAsync();

        try (BlockheadConnection clientConn = connFut.get(Timeouts.CONNECT, Timeouts.CONNECT_UNIT))
        {
            clientConn.write(new TextFrame().setPayload("hello 2"));

            LinkedBlockingQueue<WebSocketFrame> frames = clientConn.getFrameQueue();
            WebSocketFrame received = frames.poll(Timeouts.POLL_EVENT, Timeouts.POLL_EVENT_UNIT);
            String payload = received.getPayloadAsUTF8();

            // If we can connect and send a text message, we know that the endpoint was
            // added properly, and the response will help us verify the policy configuration too
            assertThat("payload", payload, containsString("session.maxTextMessageSize=" + (10 * 1024 * 1024)));
        }
    }
}
