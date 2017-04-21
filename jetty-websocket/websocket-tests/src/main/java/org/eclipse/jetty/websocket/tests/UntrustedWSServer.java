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

package org.eclipse.jetty.websocket.tests;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.websocket.api.util.WSURI;

public class UntrustedWSServer extends ContainerLifeCycle implements UntrustedWSSessionFactory.Listener
{
    private static final Logger LOG = Log.getLogger(SimpleServletServer.class);
    private Server server;
    private ServerConnector connector;
    private URI wsUri;
    private boolean ssl = false;
    private SslContextFactory sslContextFactory;
    
    private Map<URI, CompletableFuture<UntrustedWSSession>> connectionFutures = new ConcurrentHashMap<>();
    
    @Override
    protected void doStart() throws Exception
    {
        // Configure Server
        server = new Server();
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
            sslContextFactory.setExcludeCipherSuites("SSL_RSA_WITH_DES_CBC_SHA","SSL_DHE_RSA_WITH_DES_CBC_SHA","SSL_DHE_DSS_WITH_DES_CBC_SHA",
                    "SSL_RSA_EXPORT_WITH_RC4_40_MD5","SSL_RSA_EXPORT_WITH_DES40_CBC_SHA","SSL_DHE_RSA_EXPORT_WITH_DES40_CBC_SHA",
                    "SSL_DHE_DSS_EXPORT_WITH_DES40_CBC_SHA");
        
            // SSL HTTP Configuration
            HttpConfiguration https_config = new HttpConfiguration(http_config);
            https_config.addCustomizer(new SecureRequestCustomizer());
        
            // SSL Connector
            connector = new ServerConnector(server,new SslConnectionFactory(sslContextFactory, HttpVersion.HTTP_1_1.asString()),new HttpConnectionFactory(https_config));
            connector.setPort(0);
        }
        else
        {
            // Basic HTTP connector
            connector = new ServerConnector(server);
            connector.setPort(0);
        }
        server.addConnector(connector);
    
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        server.setHandler(context);
    
        // Serve untrusted endpoint
        context.addServlet(UntrustedWSServlet.class, "/untrusted/*").setInitOrder(1);
    
        // Start Server
        addBean(server);
    
        super.doStart();
        
        // Wireup Context related things
        UntrustedWSSessionFactory sessionFactory = (UntrustedWSSessionFactory) context.getServletContext().getAttribute(UntrustedWSSessionFactory.class.getName());
        sessionFactory.addListener(this);
    
        // Establish the Server URI
        URI serverUri = server.getURI();
        wsUri = WSURI.toWebsocket(serverUri).resolve("/");
    
        // Some debugging
        if (LOG.isDebugEnabled())
        {
            LOG.debug("WebSocket Server URI: " + wsUri.toASCIIString());
            LOG.debug(server.dump());
        }
        
        super.doStart();
    }
    
    public URI getWsUri()
    {
        return wsUri;
    }
    
    @Override
    public void onSessionCreate(UntrustedWSSession session, URI requestURI)
    {
        // A new session was created (but not connected, yet)
        CompletableFuture<UntrustedWSSession> sessionFuture = this.connectionFutures.get(requestURI);
        if(sessionFuture != null)
        {
            session.getUntrustedEndpoint().setConnectFuture(sessionFuture);
        }
        
        this.connectionFutures.put(requestURI, session.getUntrustedEndpoint().getConnectFuture());
    }
    
    public void registerConnectFuture(URI uri, CompletableFuture<UntrustedWSSession> sessionFuture)
    {
        this.connectionFutures.put(uri, sessionFuture);
    }
}
