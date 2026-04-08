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

package org.eclipse.jetty.ee.websocket.jakarta.tests;

import java.net.URI;
import java.util.Map;

import jakarta.websocket.OnMessage;
import jakarta.websocket.server.ServerEndpoint;
import org.eclipse.jetty.ee.servlet.ServletContextHandler;
import org.eclipse.jetty.ee.websocket.jakarta.common.JakartaWebSocketSession;
import org.eclipse.jetty.ee.websocket.jakarta.common.JakartaWebSocketSessionListener;
import org.eclipse.jetty.ee.websocket.jakarta.server.JakartaWebSocketServerContainer;
import org.eclipse.jetty.ee.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LocalServer extends ContainerLifeCycle
{
    @ServerEndpoint("/echo/text")
    public static class TextEchoSocket
    {
        @OnMessage
        public String echo(String msg)
        {
            return msg;
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(LocalServer.class);
    private final Server server;
    private ServletContextHandler servletContextHandler;
    private final TrackingListener trackingListener = new TrackingListener();
    private URI serverUri;
    private URI wsUri;

    public LocalServer()
    {
        QueuedThreadPool threadPool = new QueuedThreadPool();
        threadPool.setName("qtp-LocalServer");

        // Configure Server
        server = new Server(threadPool);
    }

    public URI getServerUri()
    {
        return serverUri;
    }

    public ServletContextHandler getServletContextHandler()
    {
        return servletContextHandler;
    }

    public URI getWsUri()
    {
        return wsUri;
    }

    /**
     * Get a WSURI with query parameters identifying the testcase.
     *
     * @param testClazz the test class
     * @param testName the test name
     * @return the {@code ws://} URI with query parameters
     */
    public URI getWsUri(Class<?> testClazz, String testName)
    {
        return wsUri.resolve("?testclass=" + testClazz + "&testname=" + testName);
    }

    public URI getTestWsUri(Class<?> clazz, String testName)
    {
        return wsUri.resolve("/test/" + clazz.getSimpleName() + "/" + testName);
    }

    public WebSocketTester newWebSocketTester(String requestPath) throws Exception
    {
        return new WebSocketTester(getWsUri().resolve(requestPath));
    }

    public WebSocketTester newWebSocketTester(String requestPath, Map<String, String> upgradeRequest) throws Exception
    {
        return new WebSocketTester(getWsUri().resolve(requestPath), upgradeRequest);
    }

    protected Handler createRootHandler(Server server)
    {
        servletContextHandler = new ServletContextHandler("/", true, false);
        server.setHandler(servletContextHandler);
        servletContextHandler.setContextPath("/");
        JakartaWebSocketServletContainerInitializer.configure(servletContextHandler, (context, container) ->
            ((JakartaWebSocketServerContainer)container).addSessionListener(trackingListener));
        return servletContextHandler;
    }

    @Override
    protected void doStart() throws Exception
    {
        ServerConnector connector;
        // Basic HTTP connector
        connector = new ServerConnector(server);

        // Add network connector
        server.addConnector(connector);

        // Add Local Connector
        LocalConnector localConnector = new LocalConnector(server);
        server.addConnector(localConnector);

        Handler rootHandler = createRootHandler(server);
        server.setHandler(rootHandler);

        // Start Server
        addBean(server);

        super.doStart();

        // Establish the Server URI
        String host = connector.getHost();
        if (host == null)
            host = "localhost";
        int port = connector.getLocalPort();
        serverUri = new URI(String.format("%s://%s:%d/", "http", host, port));
        wsUri = WSURI.toWebsocket(serverUri);

        // Some debugging
        if (LOG.isDebugEnabled())
        {
            LOG.debug(server.dump());
        }
    }

    public JakartaWebSocketServerContainer getServerContainer()
    {
        if (!servletContextHandler.isRunning())
            throw new IllegalStateException("Cannot access ServerContainer when ServletContextHandler isn't running");

        return JakartaWebSocketServerContainer.getContainer(servletContextHandler.getServletContext());
    }

    public Server getServer()
    {
        return server;
    }

    public TrackingListener getTrackingListener()
    {
        return trackingListener;
    }

    public static class TrackingListener implements JakartaWebSocketSessionListener
    {
        private final BlockingArrayQueue<JakartaWebSocketSession> openedSessions = new BlockingArrayQueue<>();
        private final BlockingArrayQueue<JakartaWebSocketSession> closedSessions = new BlockingArrayQueue<>();

        @Override
        public void onJakartaWebSocketSessionOpened(JakartaWebSocketSession session)
        {
            openedSessions.offer(session);
        }

        @Override
        public void onJakartaWebSocketSessionClosed(JakartaWebSocketSession session)
        {
            closedSessions.offer(session);
        }

        public BlockingArrayQueue<JakartaWebSocketSession> getOpenedSessions()
        {
            return openedSessions;
        }

        public BlockingArrayQueue<JakartaWebSocketSession> getClosedSessions()
        {
            return closedSessions;
        }
    }
}
