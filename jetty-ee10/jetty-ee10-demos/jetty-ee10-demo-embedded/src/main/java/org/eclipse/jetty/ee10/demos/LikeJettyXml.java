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

package org.eclipse.jetty.ee10.demos;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.deploy.providers.ContextProvider;
import org.eclipse.jetty.ee.Deployable;
import org.eclipse.jetty.ee10.annotations.AnnotationConfiguration;
import org.eclipse.jetty.ee10.plus.webapp.EnvConfiguration;
import org.eclipse.jetty.ee10.plus.webapp.PlusConfiguration;
import org.eclipse.jetty.ee10.servlet.DebugListener;
import org.eclipse.jetty.ee10.servlet.security.HashLoginService;
import org.eclipse.jetty.ee10.webapp.Configurations;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.ConnectionStatistics;
import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.rewrite.handler.InvalidURIRule;
import org.eclipse.jetty.rewrite.handler.RewriteHandler;
import org.eclipse.jetty.server.AsyncRequestLogWriter;
import org.eclipse.jetty.server.CustomRequestLog;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.LowResourceMonitor;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.StatisticsHandler;
import org.eclipse.jetty.util.component.Environment;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.util.resource.Resources;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.xml.EnvironmentBuilder;
import org.slf4j.LoggerFactory;

/**
 * Starts the Jetty Distribution's demo-base directory using entirely
 * embedded jetty techniques.
 */
public class LikeJettyXml
{
    public static Server createServer(int port, int securePort, boolean addDebugListener) throws Exception
    {
        Path configDir = Path.of("src/main/resources/demo").toAbsolutePath();
        Path runtimeDir = Path.of("target/embedded/" + LikeJettyXml.class.getSimpleName()).toAbsolutePath();
        mkdir(runtimeDir);

        // === jetty.xml ===
        // Setup Threadpool
        QueuedThreadPool threadPool = new QueuedThreadPool();
        threadPool.setMaxThreads(500);

        // Server
        Server server = new Server(threadPool);

        // Scheduler
        server.addBean(new ScheduledExecutorScheduler(null, false, -1));

        // HTTP Configuration
        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.setSecureScheme("https");
        httpConfig.setSecurePort(securePort);
        httpConfig.setOutputBufferSize(32768);
        httpConfig.setRequestHeaderSize(8192);
        httpConfig.setResponseHeaderSize(8192);
        httpConfig.setSendServerVersion(true);
        httpConfig.setSendDateHeader(false);
        // httpConfig.addCustomizer(new ForwardedRequestCustomizer());

        // Handler Structure
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        DefaultHandler defaultHandler = new DefaultHandler();

        Handler.Collection handlers = new Handler.Collection();
        handlers.setHandlers(contexts, defaultHandler);
        server.setHandler(handlers);

        // === jetty-jmx.xml ===
        MBeanContainer mbContainer = new MBeanContainer(ManagementFactory.getPlatformMBeanServer());
        mbContainer.beanAdded(null, LoggerFactory.getILoggerFactory());
        server.addBean(mbContainer);

        // === jetty-http.xml ===
        ServerConnector http = new ServerConnector(server, new HttpConnectionFactory(httpConfig));
        http.setHost("0.0.0.0");
        http.setPort(port);
        http.setIdleTimeout(30000);
        server.addConnector(http);

        // === jetty-ssl-context.xml ===
        // SSL Context Factory
        Path keystorePath = Paths.get("src/main/resources/etc/keystore.p12").toAbsolutePath();
        if (!Files.exists(keystorePath))
            throw new FileNotFoundException(keystorePath.toString());
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath(keystorePath.toString());
        sslContextFactory.setKeyStorePassword("storepwd");
        sslContextFactory.setTrustStorePath(keystorePath.toString());
        sslContextFactory.setTrustStorePassword("storepwd");

        // === jetty-ssl.xml ===
        // SSL HTTP Configuration
        HttpConfiguration httpsConfig = new HttpConfiguration(httpConfig);
        httpsConfig.addCustomizer(new SecureRequestCustomizer());

        // === jetty-https.xml ===
        // SSL Connector
        ServerConnector sslConnector = new ServerConnector(server,
            new SslConnectionFactory(sslContextFactory, HttpVersion.HTTP_1_1.asString()),
            new HttpConnectionFactory(httpsConfig));
        sslConnector.setPort(securePort);
        server.addConnector(sslConnector);

        // === jetty-deploy.xml ===
        DeploymentManager deployer = new DeploymentManager();
        if (addDebugListener)
        {
            DebugListener debug = new DebugListener(System.err, true, true, true);
            server.addBean(debug);
        }
        deployer.setContexts(contexts);

        Path webappsDir = runtimeDir.resolve("webapps");
        mkdir(webappsDir);

        Path testWebapp = webappsDir.resolve("test.war");
        if (!Files.exists(testWebapp))
        {
            Path testWebappSrc = JettyDemos.find("jetty-ee10-demo-simple-webapp/target/jetty-ee10-demo-simple-webapp-@VER@.war");
            Files.copy(testWebappSrc, testWebapp);
        }

        // == Build ee10 Environment ==
        // Support for environment specific classpath / modulepath goes here
        String environmentName = "ee10";
        Environment ee10 = Environment.get(environmentName);
        if (ee10 == null)
        {
            ee10 = new EnvironmentBuilder(environmentName).build();
            Environment.set(ee10);
        }

        // === jetty-ee10-deploy.xml ===
        ee10.setAttribute("contextHandlerClass", org.eclipse.jetty.ee10.webapp.WebAppContext.class.getName());
        ContextProvider webAppProvider = new ContextProvider();
        webAppProvider.setEnvironmentName(environmentName);
        webAppProvider.setMonitoredDirName(webappsDir.toString());
        webAppProvider.setDefaultsDescriptor(configDir.resolve("webdefault-ee10.xml").toString());
        webAppProvider.setScanInterval(1);
        webAppProvider.setExtractWars(true);
        webAppProvider.getProperties().put(Deployable.CONTAINER_SCAN_JARS,
            ".*/jakarta.servlet-api-[^/]*\\.jar$|.*jakarta.servlet.jsp.jstl-.*\\.jar$|.*/[^/]*taglibs.*\\.jar$");

        deployer.addAppProvider(webAppProvider);
        server.addBean(deployer);

        // === setup jetty plus ==
        Configurations.setServerDefault(server)
            .add(new EnvConfiguration(), new PlusConfiguration(), new AnnotationConfiguration());

        // === jetty-stats.xml ===
        StatisticsHandler stats = new StatisticsHandler();
        stats.setHandler(server.getHandler());
        server.setHandler(stats);
        server.addBeanToAllConnectors(new ConnectionStatistics());

        // === Rewrite Handler
        RewriteHandler rewrite = new RewriteHandler();
        rewrite.setHandler(server.getHandler());
        server.setHandler(rewrite);
        rewrite.addRule(new InvalidURIRule());

        // === jetty-requestlog.xml ===
        Path logsDir = runtimeDir.resolve("logs");
        mkdir(logsDir);
        AsyncRequestLogWriter logWriter = new AsyncRequestLogWriter(logsDir.resolve("yyyy_mm_dd.request.log").toString());
        logWriter.setFilenameDateFormat("yyyy_MM_dd");
        logWriter.setRetainDays(90);
        logWriter.setTimeZone("GMT");
        CustomRequestLog requestLog = new CustomRequestLog(logWriter, CustomRequestLog.EXTENDED_NCSA_FORMAT + " \"%C\"");
        server.setRequestLog(requestLog);

        // === jetty-lowresources.xml ===
        LowResourceMonitor lowResourcesMonitor = new LowResourceMonitor(server);
        lowResourcesMonitor.setPeriod(1000);
        lowResourcesMonitor.setLowResourcesIdleTimeout(200);
        lowResourcesMonitor.setMonitorThreads(true);
        lowResourcesMonitor.setMaxMemory(0);
        lowResourcesMonitor.setMaxLowResourcesTime(5000);
        server.addBean(lowResourcesMonitor);

        // === Realm ===
        HashLoginService login = new HashLoginService();
        login.setName("Test Realm");
        Path realmFile = configDir.resolve("ee10-demo-realm.properties");
        Resource realmResource = ResourceFactory.of(server).newResource(realmFile);
        if (!Resources.isReadableFile(realmResource))
            throw new FileNotFoundException("Unable to find config: " + realmFile);
        login.setConfig(realmResource);
        login.setHotReload(false);
        server.addBean(login);

        return server;
    }

    private static void mkdir(Path path) throws IOException
    {
        if (Files.exists(path))
            return;
        Files.createDirectories(path);
    }

    public static void main(String[] args) throws Exception
    {
        int port = ExampleUtil.getPort(args, "jetty.http.port", 8080);
        int securePort = ExampleUtil.getPort(args, "jetty.https.port", 8443);
        Server server = createServer(port, securePort, true);

        // Extra options
        server.setDumpAfterStart(true);
        server.setDumpBeforeStop(false);
        server.setStopAtShutdown(true);

        // Start the server
        server.start();
        server.join();
    }
}
