//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.embedded;

import java.lang.management.ManagementFactory;

import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.deploy.providers.WebAppProvider;
import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.server.AsyncNCSARequestLog;
import org.eclipse.jetty.server.ForwardedRequestCustomizer;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.NCSARequestLog;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.handler.RequestLogHandler;
import org.eclipse.jetty.server.handler.StatisticsHandler;
import org.eclipse.jetty.spdy.server.NPNServerConnectionFactory;
import org.eclipse.jetty.spdy.server.SPDYServerConnectionFactory;
import org.eclipse.jetty.spdy.server.http.HTTPSPDYServerConnectionFactory;
import org.eclipse.jetty.spdy.server.http.PushStrategy;
import org.eclipse.jetty.spdy.server.http.ReferrerPushStrategy;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

public class SpdyServer
{
    public static void main(String[] args) throws Exception
    {
        String jetty_home = System.getProperty("jetty.home","../../jetty-distribution/target/distribution");
        System.setProperty("jetty.home",jetty_home);

        // Setup Threadpool
        QueuedThreadPool threadPool = new QueuedThreadPool(512);

        // Setup Jetty Server instance
        Server server = new Server(threadPool);
        server.manage(threadPool);
        server.setDumpAfterStart(false);
        server.setDumpBeforeStop(false);

        // Setup JMX
        MBeanContainer mbContainer=new MBeanContainer(ManagementFactory.getPlatformMBeanServer());
        server.addBean(mbContainer);


        // Common HTTP configuration
        HttpConfiguration config = new HttpConfiguration();
        config.setSecurePort(8443);
        config.addCustomizer(new ForwardedRequestCustomizer());
        config.addCustomizer(new SecureRequestCustomizer());
        config.setSendServerVersion(true);


        // Http Connector Setup

        // A plain HTTP connector listening on port 8080. Note that it's also possible to have port 8080 configured as
        // a non SSL SPDY connector. But the specification and most browsers do not allow to use SPDY without SSL
        // encryption. However some browsers allow it to be configured.
        HttpConnectionFactory http = new HttpConnectionFactory(config);
        ServerConnector httpConnector = new ServerConnector(server,http);
        httpConnector.setPort(8080);
        httpConnector.setIdleTimeout(10000);
        server.addConnector(httpConnector);
        
        // SSL configurations

        // We need a SSLContextFactory for the SSL encryption. That SSLContextFactory will be used by the SPDY
        // connector.
        SslContextFactory sslContextFactory = new SslContextFactory();
        sslContextFactory.setKeyStorePath(jetty_home + "/etc/keystore");
        sslContextFactory.setKeyStorePassword("OBF:1vny1zlo1x8e1vnw1vn61x8g1zlu1vn4");
        sslContextFactory.setKeyManagerPassword("OBF:1u2u1wml1z7s1z7a1wnl1u2g");
        sslContextFactory.setTrustStorePath(jetty_home + "/etc/keystore");
        sslContextFactory.setTrustStorePassword("OBF:1vny1zlo1x8e1vnw1vn61x8g1zlu1vn4");
        sslContextFactory.setExcludeCipherSuites(
                "SSL_RSA_WITH_DES_CBC_SHA",
                "SSL_DHE_RSA_WITH_DES_CBC_SHA",
                "SSL_DHE_DSS_WITH_DES_CBC_SHA",
                "SSL_RSA_EXPORT_WITH_RC4_40_MD5",
                "SSL_RSA_EXPORT_WITH_DES40_CBC_SHA",
                "SSL_DHE_RSA_EXPORT_WITH_DES40_CBC_SHA",
                "SSL_DHE_DSS_EXPORT_WITH_DES40_CBC_SHA");


        // Spdy Connector

        // Make sure that the required NPN implementations are available.
        SPDYServerConnectionFactory.checkProtocolNegotiationAvailable();

        // A ReferrerPushStrategy is being initialized.
        // See: http://www.eclipse.org/jetty/documentation/current/spdy-configuring-push.html for more details.
        PushStrategy push = new ReferrerPushStrategy();
        HTTPSPDYServerConnectionFactory spdy2 = new HTTPSPDYServerConnectionFactory(2,config,push);
        spdy2.setInputBufferSize(8192);
        spdy2.setInitialWindowSize(32768);

        // We need a connection factory per protocol that our server is supposed to support on the NPN port. We then
        // create a ServerConnector and pass in the supported factories. NPN will then be used to negotiate the
        // protocol with the client.
        HTTPSPDYServerConnectionFactory spdy3 = new HTTPSPDYServerConnectionFactory(3,config,push);
        spdy2.setInputBufferSize(8192);

        NPNServerConnectionFactory npn = new NPNServerConnectionFactory(spdy3.getProtocol(),spdy2.getProtocol(),http.getProtocol());
        npn.setDefaultProtocol(http.getProtocol());
        npn.setInputBufferSize(1024);
        
        SslConnectionFactory ssl = new SslConnectionFactory(sslContextFactory,npn.getProtocol());

        // Setup the npn connector on port 8443
        ServerConnector spdyConnector = new ServerConnector(server,ssl,npn,spdy3,spdy2,http);
        spdyConnector.setPort(8443);

        server.addConnector(spdyConnector);

        // The following section adds some handlers, deployers and webapp providers.
        // See: http://www.eclipse.org/jetty/documentation/current/advanced-embedding.html for details.
        
        // Setup handlers
        HandlerCollection handlers = new HandlerCollection();
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        RequestLogHandler requestLogHandler = new RequestLogHandler();

        handlers.setHandlers(new Handler[] { contexts, new DefaultHandler(), requestLogHandler });

        StatisticsHandler stats = new StatisticsHandler();
        stats.setHandler(handlers);

        server.setHandler(stats);

        // Setup deployers
        DeploymentManager deployer = new DeploymentManager();
        deployer.setContexts(contexts);
        server.addBean(deployer);

        WebAppProvider webapp_provider = new WebAppProvider();
        webapp_provider.setMonitoredDirName(jetty_home + "/webapps");
        webapp_provider.setParentLoaderPriority(false);
        webapp_provider.setExtractWars(true);
        webapp_provider.setScanInterval(2);
        webapp_provider.setDefaultsDescriptor(jetty_home + "/etc/webdefault.xml");
        deployer.addAppProvider(webapp_provider);

        HashLoginService login = new HashLoginService();
        login.setName("Test Realm");
        login.setConfig(jetty_home + "/etc/realm.properties");
        server.addBean(login);

        NCSARequestLog requestLog = new AsyncNCSARequestLog();
        requestLog.setFilename(jetty_home + "/logs/jetty-yyyy_mm_dd.log");
        requestLog.setExtended(false);
        requestLogHandler.setRequestLog(requestLog);

        server.setStopAtShutdown(true);

        server.start();
        server.dumpStdErr();
        server.join();
    }
}
