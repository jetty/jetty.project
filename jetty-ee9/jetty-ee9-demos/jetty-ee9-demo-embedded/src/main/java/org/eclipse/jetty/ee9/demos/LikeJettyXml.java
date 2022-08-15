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

package org.eclipse.jetty.ee9.demos;

/**
 * Starts the Jetty Distribution's demo-base directory using entirely
 * embedded jetty techniques.
 */
public class LikeJettyXml
{
    // TODO
    // FIXME
//    public static Server createServer(int port, int securePort, boolean addDebugListener) throws Exception
//    {
//        Path configDir = Paths.get("src/main/resources/demo").toAbsolutePath();
//        Path runtimeDir = Paths.get("target/embedded/" + LikeJettyXml.class.getSimpleName()).toAbsolutePath();
//        mkdir(runtimeDir);
//
//        // === jetty.xml ===
//        // Setup Threadpool
//        QueuedThreadPool threadPool = new QueuedThreadPool();
//        threadPool.setMaxThreads(500);
//
//        // Server
//        Server server = new Server(threadPool);
//
//        // Scheduler
//        server.addBean(new ScheduledExecutorScheduler(null, false));
//
//        // HTTP Configuration
//        HttpConfiguration httpConfig = new HttpConfiguration();
//        httpConfig.setSecureScheme("https");
//        httpConfig.setSecurePort(securePort);
//        httpConfig.setOutputBufferSize(32768);
//        httpConfig.setRequestHeaderSize(8192);
//        httpConfig.setResponseHeaderSize(8192);
//        httpConfig.setSendServerVersion(true);
//        httpConfig.setSendDateHeader(false);
//        // httpConfig.addCustomizer(new ForwardedRequestCustomizer());
//
//        // Handler Structure
//        ContextHandlerCollection contexts = new ContextHandlerCollection();
//        server.setHandler(new HandlerList(contexts, new DefaultHandler()));
//
//        // === jetty-jmx.xml ===
//        MBeanContainer mbContainer = new MBeanContainer(
//            ManagementFactory.getPlatformMBeanServer());
//        server.addBean(mbContainer);
//
//        // === jetty-http.xml ===
//        ServerConnector http = new ServerConnector(server,
//            new HttpConnectionFactory(httpConfig));
//        http.setPort(port);
//        http.setIdleTimeout(30000);
//        server.addConnector(http);
//
//        // === jetty-https.xml ===
//        // SSL Context Factory
//        Path keystorePath = Paths.get("src/main/resources/etc/keystore.p12").toAbsolutePath();
//        if (!Files.exists(keystorePath))
//            throw new FileNotFoundException(keystorePath.toString());
//        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
//        sslContextFactory.setKeyStorePath(keystorePath.toString());
//        sslContextFactory.setKeyStorePassword("storepwd");
//        sslContextFactory.setTrustStorePath(keystorePath.toString());
//        sslContextFactory.setTrustStorePassword("storepwd");
//
//        // SSL HTTP Configuration
//        HttpConfiguration httpsConfig = new HttpConfiguration(httpConfig);
//        httpsConfig.addCustomizer(new SecureRequestCustomizer());
//
//        // SSL Connector
//        ServerConnector sslConnector = new ServerConnector(server,
//            new SslConnectionFactory(sslContextFactory, HttpVersion.HTTP_1_1.asString()),
//            new HttpConnectionFactory(httpsConfig));
//        sslConnector.setPort(securePort);
//        server.addConnector(sslConnector);
//
//        // === jetty-deploy.xml ===
//        DeploymentManager deployer = new DeploymentManager();
//        if (addDebugListener)
//        {
//            DebugListener debug = new DebugListener(System.err, true, true, true);
//            server.addBean(debug);
//            deployer.addLifeCycleBinding(new DebugListenerBinding(debug));
//        }
//        deployer.setContexts(contexts);
//        deployer.setContextAttribute(
//            "org.eclipse.jetty.server.webapp.ContainerIncludeJarPattern",
//            ".*/jetty-jakarta-servlet-api-[^/]*\\.jar$|.*/jakarta.servlet.jsp.jstl-.*\\.jar$|.*/[^/]*taglibs.*\\.jar$");
//
//        Path webappsDir = runtimeDir.resolve("webapps");
//        mkdir(webappsDir);
//
//        Path testWebapp = webappsDir.resolve("test.war");
//        if (!Files.exists(testWebapp))
//        {
//            Path testWebappSrc = JettyDemos.find("demo-simple-webapp/target/demo-simple-webapp-@VER@.war");
//            Files.copy(testWebappSrc, testWebapp);
//        }
//
//        WebAppProvider webAppProvider = new WebAppProvider();
//        webAppProvider.setMonitoredDirName(webappsDir.toString());
//        webAppProvider.setDefaultsDescriptor(configDir.resolve("webdefault-ee9.xml").toString());
//        webAppProvider.setScanInterval(1);
//        webAppProvider.setExtractWars(true);
//        webAppProvider.setConfigurationManager(new PropertiesConfigurationManager());
//
//        deployer.addAppProvider(webAppProvider);
//        server.addBean(deployer);
//
//        // === setup jetty plus ==
//        Configurations.setServerDefault(server).add(new EnvConfiguration(), new PlusConfiguration(), new AnnotationConfiguration());
//
//        // === jetty-stats.xml ===
//        StatisticsHandler stats = new StatisticsHandler();
//        stats.setHandler(server.getHandler());
//        server.setHandler(stats);
//        server.addBeanToAllConnectors(new ConnectionStatistics());
//
//        // === Rewrite Handler
//        RewriteHandler rewrite = new RewriteHandler();
//        rewrite.setHandler(server.getHandler());
//        server.setHandler(rewrite);
//        rewrite.addRule(new InvalidURIRule());
//
//        // === jetty-requestlog.xml ===
//        Path logsDir = runtimeDir.resolve("logs");
//        mkdir(logsDir);
//        AsyncRequestLogWriter logWriter = new AsyncRequestLogWriter(logsDir.resolve("yyyy_mm_dd.request.log").toString());
//        logWriter.setFilenameDateFormat("yyyy_MM_dd");
//        logWriter.setRetainDays(90);
//        logWriter.setTimeZone("GMT");
//        CustomRequestLog requestLog = new CustomRequestLog(logWriter, CustomRequestLog.EXTENDED_NCSA_FORMAT + " \"%C\"");
//        server.setRequestLog(requestLog);
//
//        // === jetty-lowresources.xml ===
//        LowResourceMonitor lowResourcesMonitor = new LowResourceMonitor(server);
//        lowResourcesMonitor.setPeriod(1000);
//        lowResourcesMonitor.setLowResourcesIdleTimeout(200);
//        lowResourcesMonitor.setMonitorThreads(true);
//        lowResourcesMonitor.setMaxMemory(0);
//        lowResourcesMonitor.setMaxLowResourcesTime(5000);
//        server.addBean(lowResourcesMonitor);
//
//        // === test-realm.xml ===
//        HashLoginService login = new HashLoginService();
//        login.setName("Test Realm");
//        login.setConfig(configDir.resolve("demo-realm.properties").toString());
//        login.setHotReload(false);
//        server.addBean(login);
//
//        return server;
//    }
//
//    private static void mkdir(Path path) throws IOException
//    {
//        if (Files.exists(path))
//            return;
//        Files.createDirectories(path);
//    }
//
//    public static void main(String[] args) throws Exception
//    {
//        int port = ExampleUtil.getPort(args, "jetty.http.port", 8080);
//        int securePort = ExampleUtil.getPort(args, "jetty.https.port", 8443);
//        Server server = createServer(port, securePort, true);
//
//        // Extra options
//        server.setDumpAfterStart(true);
//        server.setDumpBeforeStop(false);
//        server.setStopAtShutdown(true);
//
//        // Start the server
//        server.start();
//        server.join();
//    }
}
