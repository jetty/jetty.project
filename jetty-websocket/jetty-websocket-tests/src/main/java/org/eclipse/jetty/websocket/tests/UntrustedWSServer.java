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

package org.eclipse.jetty.websocket.tests;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.api.WSURI;
import org.eclipse.jetty.websocket.server.WebSocketCreator;
import org.junit.rules.TestName;

public class UntrustedWSServer extends ContainerLifeCycle
{
    private static final Logger LOG = Log.getLogger(UntrustedWSServer.class);
    private Server server;
    private ServerConnector connector;
    private URI wsUri;
    private boolean ssl = false;
    private SslContextFactory sslContextFactory;
    private Consumer<Server> serverConsumer;

    private Map<URI, CompletableFuture<UntrustedWSSession>> onOpenFutures = new ConcurrentHashMap<>();
    private final ServletContextHandler context = new ServletContextHandler();

    @Override
    protected void doStart() throws Exception
    {
        QueuedThreadPool threadPool= new QueuedThreadPool();
        String name = "qtp-untrustedWSServer-" + hashCode();
        threadPool.setName(name);
        threadPool.setDaemon(true);

        // Configure Server
        server = new Server(threadPool);
        if (ssl)
        {
            // HTTP Configuration
            HttpConfiguration http_config = new HttpConfiguration();
            http_config.setSecureScheme("https");
            http_config.setSecurePort(0);
            http_config.setOutputBufferSize(32768);
            http_config.setRequestHeaderSize(8192);
            http_config.setResponseHeaderSize(8192);
            http_config.setSendServerVersion(true);
            http_config.setSendDateHeader(false);

            sslContextFactory = new SslContextFactory();
            sslContextFactory.setKeyStorePath(MavenTestingUtils.getTestResourceFile("keystore").getAbsolutePath());
            sslContextFactory.setKeyStorePassword("storepwd");
            sslContextFactory.setKeyManagerPassword("keypwd");
            sslContextFactory.setExcludeCipherSuites("SSL_RSA_WITH_DES_CBC_SHA", "SSL_DHE_RSA_WITH_DES_CBC_SHA", "SSL_DHE_DSS_WITH_DES_CBC_SHA",
                    "SSL_RSA_EXPORT_WITH_RC4_40_MD5", "SSL_RSA_EXPORT_WITH_DES40_CBC_SHA", "SSL_DHE_RSA_EXPORT_WITH_DES40_CBC_SHA",
                    "SSL_DHE_DSS_EXPORT_WITH_DES40_CBC_SHA");

            // SSL HTTP Configuration
            HttpConfiguration https_config = new HttpConfiguration(http_config);
            https_config.addCustomizer(new SecureRequestCustomizer());

            // SSL Connector
            connector = new ServerConnector(server, new SslConnectionFactory(sslContextFactory, HttpVersion.HTTP_1_1.asString()), new HttpConnectionFactory(https_config));
            connector.setPort(0);
        }
        else
        {
            // Basic HTTP connector
            connector = new ServerConnector(server);
            connector.setPort(0);
        }
        server.addConnector(connector);

        context.setContextPath("/");
        server.setHandler(context);

        // Serve untrusted endpoint
        context.addServlet(UntrustedWSServlet.class, "/untrusted/*").setInitOrder(1);

        // Allow for server customization
        if (serverConsumer != null)
        {
            serverConsumer.accept(server);
        }

        // Start Server
        addBean(server);

        super.doStart();

        // Establish the Server URI
        URI serverUri = server.getURI();
        wsUri = WSURI.toWebsocket(serverUri).resolve("/");

        // Some debugging
        if (LOG.isDebugEnabled())
        {
            LOG.debug("WebSocket Server URI: " + wsUri.toASCIIString());
            LOG.debug("{}", server.dump());
        }

        super.doStart();
    }

    public void setServerCustomizer(Consumer<Server> customizer)
    {
        this.serverConsumer = customizer;
    }

    public void join() throws InterruptedException
    {
        server.join();
    }

    public URI getWsUri()
    {
        return wsUri;
    }

    public URI getUntrustedWsUri(Class<?> clazz, TestName testname)
    {
        return wsUri.resolve("/untrusted/" + clazz.getSimpleName() + "/" + testname.getMethodName());
    }

    public void registerHttpService(String urlPattern, BiConsumer<HttpServletRequest, HttpServletResponse> serviceConsumer)
    {
        ServletHolder holder = new ServletHolder(new BiConsumerServiceServlet(serviceConsumer));
        context.addServlet(holder, urlPattern);
    }

    public void registerWebSocket(String urlPattern, WebSocketCreator creator)
    {
        ServletHolder holder = new ServletHolder(new UntrustedWSServlet(creator));
        context.addServlet(holder, urlPattern);
    }

    // TODO: wire up listener again?
    public void onSessionCreate(UntrustedWSSession session, URI requestURI)
    {
        // A new session was created (but not connected, yet)
        CompletableFuture<UntrustedWSSession> sessionFuture = this.onOpenFutures.get(requestURI);
        if (sessionFuture != null)
        {
            session.getUntrustedEndpoint().setOnOpenFuture(sessionFuture);
            this.onOpenFutures.put(requestURI, sessionFuture);
        }
    }

    public void registerOnOpenFuture(URI uri, CompletableFuture<UntrustedWSSession> sessionFuture)
    {
        this.onOpenFutures.put(uri, sessionFuture);
    }
}
