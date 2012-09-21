//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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
import org.eclipse.jetty.deploy.providers.ContextProvider;
import org.eclipse.jetty.deploy.providers.WebAppProvider;
import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.NCSARequestLog;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.handler.RequestLogHandler;
import org.eclipse.jetty.server.handler.StatisticsHandler;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

public class LikeJettyXml
{
    public static void main(String[] args) throws Exception
    {
        String jetty_home = System.getProperty("jetty.home","../jetty-distribution/target/distribution");
        System.setProperty("jetty.home",jetty_home);

        // Setup Threadpool
        QueuedThreadPool threadPool = new QueuedThreadPool();
        threadPool.setMaxThreads(500);

        Server server = new Server(threadPool);
        server.manage(threadPool);
        server.setDumpAfterStart(false);
        server.setDumpBeforeStop(false);

        // Setup JMX
        MBeanContainer mbContainer=new MBeanContainer(ManagementFactory.getPlatformMBeanServer());
        server.addBean(mbContainer);

        // Setup Connectors
        HttpConnectionFactory http = new HttpConnectionFactory();
        http.getHttpChannelConfig().setSecurePort(8443);
        ServerConnector connector = new ServerConnector(server,http);
        connector.setPort(8080);
        connector.setIdleTimeout(30000);

        server.addConnector(connector);

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
        ServerConnector sslConnector = new ServerConnector(server,sslContextFactory);
        sslConnector.setPort(8443);
        server.addConnector(sslConnector);
        sslConnector.open();

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

        ContextProvider context_provider = new ContextProvider();
        context_provider.setMonitoredDirName(jetty_home + "/contexts");
        context_provider.setScanInterval(2);
        deployer.addAppProvider(context_provider);

        WebAppProvider webapp_provider = new WebAppProvider();
        webapp_provider.setMonitoredDirName(jetty_home + "/webapps");
        webapp_provider.setParentLoaderPriority(false);
        webapp_provider.setExtractWars(true);
        webapp_provider.setScanInterval(2);
        webapp_provider.setDefaultsDescriptor(jetty_home + "/etc/webdefault.xml");
        webapp_provider.setContextXmlDir(jetty_home + "/contexts");
        deployer.addAppProvider(webapp_provider);

        HashLoginService login = new HashLoginService();
        login.setName("Test Realm");
        login.setConfig(jetty_home + "/etc/realm.properties");
        server.addBean(login);

        NCSARequestLog requestLog = new NCSARequestLog(jetty_home + "/logs/jetty-yyyy_mm_dd.log");
        requestLog.setExtended(false);
        requestLogHandler.setRequestLog(requestLog);

        server.setStopAtShutdown(true);
        server.setSendServerVersion(true);

        server.start();
        server.join();
    }
}
