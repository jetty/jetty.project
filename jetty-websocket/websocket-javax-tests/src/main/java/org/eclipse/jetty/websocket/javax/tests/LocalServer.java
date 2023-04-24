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

package org.eclipse.jetty.websocket.javax.tests;

import java.net.URI;
import java.util.Map;
import java.util.function.BiConsumer;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.websocket.OnMessage;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.tests.test.resources.TestKeyStoreFactory;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.core.internal.Parser;
import org.eclipse.jetty.websocket.javax.common.JavaxWebSocketSession;
import org.eclipse.jetty.websocket.javax.common.JavaxWebSocketSessionListener;
import org.eclipse.jetty.websocket.javax.server.config.JavaxWebSocketServletContainerInitializer;
import org.eclipse.jetty.websocket.javax.server.internal.JavaxWebSocketServerContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LocalServer extends ContainerLifeCycle implements LocalFuzzer.Provider
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
    private final ByteBufferPool bufferPool = new MappedByteBufferPool();
    private Server server;
    private ServerConnector connector;
    private LocalConnector localConnector;
    private ServletContextHandler servletContextHandler;
    private TrackingListener trackingListener = new TrackingListener();
    private URI serverUri;
    private URI wsUri;
    private boolean ssl = false;
    private SslContextFactory.Server sslContextFactory;

    public LocalServer()
    {
        QueuedThreadPool threadPool = new QueuedThreadPool();
        threadPool.setName("qtp-LocalServer");

        // Configure Server
        server = new Server(threadPool);
    }

    public void enableSsl(boolean ssl)
    {
        this.ssl = ssl;
    }

    public LocalConnector getLocalConnector()
    {
        return localConnector;
    }

    public URI getServerUri()
    {
        return serverUri;
    }

    public ServletContextHandler getServletContextHandler()
    {
        return servletContextHandler;
    }

    public SslContextFactory getSslContextFactory()
    {
        return sslContextFactory;
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

    public boolean isSslEnabled()
    {
        return ssl;
    }

    @Override
    public Parser newClientParser()
    {
        return new Parser(bufferPool);
    }

    @Override
    public LocalConnector.LocalEndPoint newLocalConnection()
    {
        return getLocalConnector().connect();
    }

    public Fuzzer newNetworkFuzzer() throws Exception
    {
        return new NetworkFuzzer(this);
    }

    public Fuzzer newNetworkFuzzer(CharSequence requestPath) throws Exception
    {
        return new NetworkFuzzer(this, getWsUri().resolve(requestPath.toString()));
    }

    public Fuzzer newNetworkFuzzer(CharSequence requestPath, Map<String, String> upgradeRequest) throws Exception
    {
        return new NetworkFuzzer(this, getWsUri().resolve(requestPath.toString()), upgradeRequest);
    }

    protected Handler createRootHandler(Server server) throws Exception
    {
        servletContextHandler = new ServletContextHandler(server, "/", true, false);
        servletContextHandler.setContextPath("/");
        JavaxWebSocketServletContainerInitializer.configure(servletContextHandler, (context, container) ->
            ((JavaxWebSocketServerContainer)container).addSessionListener(trackingListener));
        configureServletContextHandler(servletContextHandler);
        return servletContextHandler;
    }

    protected void configureServletContextHandler(ServletContextHandler context) throws Exception
    {
        /* override to change context handler */
    }

    @Override
    protected void doStart() throws Exception
    {
        if (ssl)
        {
            // HTTP Configuration
            HttpConfiguration httpConfig = new HttpConfiguration();
            httpConfig.setSecureScheme("https");
            httpConfig.setSecurePort(0);
            httpConfig.setOutputBufferSize(32768);
            httpConfig.setRequestHeaderSize(8192);
            httpConfig.setResponseHeaderSize(8192);
            httpConfig.setSendServerVersion(true);
            httpConfig.setSendDateHeader(false);

            sslContextFactory = new SslContextFactory.Server();
            sslContextFactory.setKeyStore(TestKeyStoreFactory.getServerKeyStore());
            sslContextFactory.setKeyStorePassword(TestKeyStoreFactory.KEY_STORE_PASSWORD);
            sslContextFactory.setTrustStore(TestKeyStoreFactory.getTrustStore());
            sslContextFactory.setTrustStorePassword(TestKeyStoreFactory.KEY_STORE_PASSWORD);
            sslContextFactory.setExcludeCipherSuites("SSL_RSA_WITH_DES_CBC_SHA", "SSL_DHE_RSA_WITH_DES_CBC_SHA", "SSL_DHE_DSS_WITH_DES_CBC_SHA",
                "SSL_RSA_EXPORT_WITH_RC4_40_MD5", "SSL_RSA_EXPORT_WITH_DES40_CBC_SHA", "SSL_DHE_RSA_EXPORT_WITH_DES40_CBC_SHA",
                "SSL_DHE_DSS_EXPORT_WITH_DES40_CBC_SHA");

            // SSL HTTP Configuration
            HttpConfiguration httpsConfig = new HttpConfiguration(httpConfig);
            httpsConfig.addCustomizer(new SecureRequestCustomizer());

            // SSL Connector
            connector = new ServerConnector(server, new SslConnectionFactory(sslContextFactory, HttpVersion.HTTP_1_1.asString()),
                new HttpConnectionFactory(httpsConfig));
        }
        else
        {
            // Basic HTTP connector
            connector = new ServerConnector(server);
        }

        // Add network connector
        server.addConnector(connector);

        // Add Local Connector
        localConnector = new LocalConnector(server);
        server.addConnector(localConnector);

        Handler rootHandler = createRootHandler(server);
        server.setHandler(rootHandler);

        // Start Server
        addBean(server);

        super.doStart();

        // Establish the Server URI
        String host = connector.getHost();
        if (host == null)
        {
            host = "localhost";
        }
        int port = connector.getLocalPort();
        serverUri = new URI(String.format("%s://%s:%d/", ssl ? "https" : "http", host, port));
        wsUri = WSURI.toWebsocket(serverUri);

        // Some debugging
        if (LOG.isDebugEnabled())
        {
            LOG.debug(server.dump());
        }
    }

    public void registerHttpService(String urlPattern, BiConsumer<HttpServletRequest, HttpServletResponse> serviceConsumer)
    {
        ServletHolder holder = new ServletHolder(new BiConsumerServiceServlet(serviceConsumer));
        servletContextHandler.addServlet(holder, urlPattern);
    }

    public JavaxWebSocketServerContainer getServerContainer()
    {
        if (!servletContextHandler.isRunning())
            throw new IllegalStateException("Cannot access ServerContainer when ServletContextHandler isn't running");

        return JavaxWebSocketServerContainer.getContainer(servletContextHandler.getServletContext());
    }

    public Server getServer()
    {
        return server;
    }

    public TrackingListener getTrackingListener()
    {
        return trackingListener;
    }

    public static class TrackingListener implements JavaxWebSocketSessionListener
    {
        private BlockingArrayQueue<JavaxWebSocketSession> openedSessions = new BlockingArrayQueue<>();
        private BlockingArrayQueue<JavaxWebSocketSession> closedSessions = new BlockingArrayQueue<>();

        @Override
        public void onJavaxWebSocketSessionOpened(JavaxWebSocketSession session)
        {
            openedSessions.offer(session);
        }

        @Override
        public void onJavaxWebSocketSessionClosed(JavaxWebSocketSession session)
        {
            closedSessions.offer(session);
        }

        public BlockingArrayQueue<JavaxWebSocketSession> getOpenedSessions()
        {
            return openedSessions;
        }

        public BlockingArrayQueue<JavaxWebSocketSession> getClosedSessions()
        {
            return closedSessions;
        }
    }
}
