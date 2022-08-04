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

package org.eclipse.jetty.ee10;

import java.io.FileNotFoundException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.ee10.servlet.security.HashLoginService;
import org.eclipse.jetty.ee10.webapp.Configurations;
import org.eclipse.jetty.ee10.webapp.MetaInfConfiguration;
import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.server.CustomRequestLog;
import org.eclipse.jetty.server.ForwardedRequestCustomizer;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.session.DefaultSessionCache;
import org.eclipse.jetty.session.FileSessionDataStore;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.Disabled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Disabled("Not a test case")
public class TestServer
{
    private static final Logger LOG = LoggerFactory.getLogger(TestServer.class);

    public static void main(String[] args) throws Exception
    {
        Path webappProjectRoot = MavenTestingUtils.getBasePath();

        // Setup Threadpool
        QueuedThreadPool threadPool = new QueuedThreadPool();
        threadPool.setMaxThreads(100);

        // Setup server
        Server server = new Server(threadPool);
        Configurations.setServerDefault(server);
        server.manage(threadPool);

        ResourceFactory resourceFactory = ResourceFactory.of(server);

        // Setup JMX
        MBeanContainer mbContainer = new MBeanContainer(ManagementFactory.getPlatformMBeanServer());
        server.addBean(mbContainer);

        // Common HTTP configuration
        HttpConfiguration config = new HttpConfiguration();
        config.setSecurePort(8443);
        config.addCustomizer(new ForwardedRequestCustomizer());
        config.addCustomizer(new SecureRequestCustomizer());
        config.setSendDateHeader(true);
        config.setSendServerVersion(true);

        // Http Connector
        HttpConnectionFactory http = new HttpConnectionFactory(config);
        ServerConnector httpConnector = new ServerConnector(server, http);
        httpConnector.setPort(8080);
        httpConnector.setIdleTimeout(30000);
        server.addConnector(httpConnector);

        // Handlers
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        Handler.Collection handlers = new Handler.Collection(contexts, new DefaultHandler());

        // Add restart handler to test the ability to save sessions and restart
        /* TODO: figure out how to do this
        RestartHandler restart = new RestartHandler();
        restart.setHandler(handlers);
        server.setHandler(restart);
        */

        // Setup context
        HashLoginService login = new HashLoginService();
        login.setName("Test Realm");
        Path realmPropPath = webappProjectRoot.resolve("src/test/resources/test-realm.properties");
        if (!Files.exists(realmPropPath))
            throw new FileNotFoundException(realmPropPath.toString());
        Resource realmResource = resourceFactory.newResource(realmPropPath);
        login.setConfig(realmResource);
        server.addBean(login);

        Path logPath = Files.createTempFile("jetty-yyyy_mm_dd", "log");
        CustomRequestLog requestLog = new CustomRequestLog(logPath.toString());
        server.setRequestLog(requestLog);

        server.setStopAtShutdown(true);

        WebAppContext webapp = new WebAppContext();
        webapp.setContextPath("/test");
        webapp.setParentLoaderPriority(true);
        Path webappBase = webappProjectRoot.resolve("src/main/webapp");
        if (!Files.exists(webappBase))
            throw new FileNotFoundException(webappBase.toString());
        webapp.setBaseResource(resourceFactory.newResource(webappBase));
        webapp.setAttribute(MetaInfConfiguration.CONTAINER_JAR_PATTERN,
            ".*/test-jetty-webapp/target/classes.*$|" +
                ".*/jakarta.servlet.api-[^/]*\\.jar$|.*/jakarta.servlet.jsp.jstl-.*\\.jar$|.*/org.apache.taglibs.taglibs-standard.*\\.jar$"
        );

        webapp.setAttribute("testAttribute", "testValue");
        Path sessionDir = Files.createTempDirectory("sessions");
        DefaultSessionCache ss = new DefaultSessionCache(webapp.getSessionHandler());
        FileSessionDataStore sds = new FileSessionDataStore();
        ss.setSessionDataStore(sds);
        sds.setStoreDir(sessionDir.toFile());
        webapp.getSessionHandler().setSessionCache(ss);

        contexts.addHandler(webapp);

        ContextHandler srcroot = new ContextHandler();
        Path srcRootPath = webappProjectRoot.resolve("src");
        if (!Files.exists(srcRootPath))
            throw new FileNotFoundException(srcRootPath.toString());
        srcroot.setBaseResource(resourceFactory.newResource(srcRootPath));
        srcroot.setHandler(new ResourceHandler());
        srcroot.setContextPath("/src");
        contexts.addHandler(srcroot);

        server.setHandler(contexts);
        server.start();
        server.dumpStdErr();

        server.join();
    }

    //TODO how to restart server?
    /*
    private static class RestartHandler extends HandlerWrapper
    {
    
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            super.handle(target, baseRequest, request, response);
            if (Boolean.valueOf(request.getParameter("restart")))
            {
                final Server server = getServer();
    
                new Thread()
                {
                    @Override
                    public void run()
                    {
                        try
                        {
                            Thread.sleep(100);
                            server.stop();
                            Thread.sleep(100);
                            server.start();
                        }
                        catch (Exception e)
                        {
                            LOG.warn("Unable to restart server", e);
                        }
                    }
                }.start();
            }
        }
    }*/
}
