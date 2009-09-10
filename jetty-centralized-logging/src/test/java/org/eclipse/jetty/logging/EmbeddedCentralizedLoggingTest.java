// ========================================================================
// Copyright (c) Webtide LLC
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
//
// The Apache License v2.0 is available at
// http://www.apache.org/licenses/LICENSE-2.0.txt
//
// You may elect to redistribute this code under either of these licenses.
// ========================================================================
package org.eclipse.jetty.logging;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import junit.framework.TestCase;

import org.eclipse.jetty.logging.impl.CentralLoggerConfig;
import org.eclipse.jetty.logging.impl.Severity;
import org.eclipse.jetty.logging.impl.TestAppender;
import org.eclipse.jetty.logging.impl.TestAppender.LogEvent;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.webapp.Configuration;
import org.eclipse.jetty.webapp.WebAppContext;

/**
 * Test centralized logging in an embedded scenario
 */
public class EmbeddedCentralizedLoggingTest extends TestCase
{
    private static final String LOGGING_SERVLET_ID = "org.eclipse.jetty.tests.webapp.LoggingServlet";
    private TestAppender testAppender;

    private void assertContainsLogEvents(TestAppender capturedEvents, LogEvent[] expectedLogs)
    {
        for (LogEvent expectedEvent : expectedLogs)
        {
            if (!capturedEvents.contains(expectedEvent))
            {
                capturedEvents.dump();
                fail("LogEvent not found: " + expectedEvent);
            }
        }
    }

    private Handler createWebapp(String contextPath, String webappName)
    {
        File webappFile = MavenTestingUtils.getTestResourceFile("webapps/" + webappName);

        WebAppContext webapp = new WebAppContext();
        webapp.setContextPath(contextPath);
        webapp.setWar(webappFile.getAbsolutePath());

        return webapp;
    }

    protected Server createWebAppServer(String contextPath, String webappName) throws Exception
    {
        if (!CentralizedWebAppLoggingConfiguration.isLoggerConfigured())
        {
            String loggerConfigFilename = MavenTestingUtils.getTestResourceFile("logger/testing.properties").getAbsolutePath();
            CentralizedWebAppLoggingConfiguration.setLoggerConfigurationFilename(loggerConfigFilename);
        }

        CentralLoggerConfig root = CentralizedWebAppLoggingConfiguration.getLoggerRoot();
        testAppender = (TestAppender)root.findAppender(TestAppender.class);
        testAppender.reset();

        Server server = new Server();
        List<Configuration> serverConfigs = new ArrayList<Configuration>();
        serverConfigs.add(new CentralizedWebAppLoggingConfiguration());
        server.setAttribute(WebAppContext.SERVER_CONFIG,serverConfigs);

        Connector connector = new SelectChannelConnector();
        connector.setPort(0);
        server.setConnectors(new Connector[]
        { connector });

        File webappFile = MavenTestingUtils.getTestResourceFile("webapps/" + webappName);

        WebAppContext webapp = new WebAppContext();
        webapp.setContextPath(contextPath);
        webapp.setWar(webappFile.getAbsolutePath());

        server.setHandler(webapp);

        return server;
    }

    @Override
    protected void setUp() throws Exception
    {
        super.setUp();

        File testTmpDir = new File(MavenTestingUtils.getTargetTestingDir(this),"workdir");
        testTmpDir.mkdirs();
        System.setProperty("java.io.tmpdir",testTmpDir.getAbsolutePath());
    }

    public void testEmbeddedAll() throws Exception
    {
        if (!CentralizedWebAppLoggingConfiguration.isLoggerConfigured())
        {
            String loggerConfigFilename = MavenTestingUtils.getTestResourceFile("logger/testing.properties").getAbsolutePath();
            CentralizedWebAppLoggingConfiguration.setLoggerConfigurationFilename(loggerConfigFilename);
        }

        CentralLoggerConfig root = CentralizedWebAppLoggingConfiguration.getLoggerRoot();
        testAppender = (TestAppender)root.findAppender(TestAppender.class);
        testAppender.reset();

        Server server = new Server();
        List<Configuration> serverConfigs = new ArrayList<Configuration>();
        serverConfigs.add(new CentralizedWebAppLoggingConfiguration());
        server.setAttribute(WebAppContext.SERVER_CONFIG,serverConfigs);

        Connector connector = new SelectChannelConnector();
        connector.setPort(0);
        server.setConnectors(new Connector[]
        { connector });

        ContextHandlerCollection handlers = new ContextHandlerCollection();
        handlers.addHandler(createWebapp("/log4j","dummy-webapp-logging-log4j.war"));
        handlers.addHandler(createWebapp("/slf4j","dummy-webapp-logging-slf4j.war"));
        handlers.addHandler(createWebapp("/clogging","dummy-webapp-logging-commons.war"));
        handlers.addHandler(createWebapp("/javalogging","dummy-webapp-logging-java.war"));

        server.setHandler(handlers);

        server.start();

        SimpleRequest.get(server,"/log4j/logging");
        SimpleRequest.get(server,"/slf4j/logging");
        SimpleRequest.get(server,"/clogging/logging");
        SimpleRequest.get(server,"/javalogging/logging");

        server.stop();

        TestAppender.LogEvent expectedLogs[] =
        { new LogEvent(null,-1,Severity.DEBUG,LOGGING_SERVLET_ID,"LoggingServlet(log4j) initialized",null),
                new LogEvent(null,-1,Severity.INFO,LOGGING_SERVLET_ID,"LoggingServlet(log4j) GET requested",null),
                new LogEvent(null,-1,Severity.DEBUG,LOGGING_SERVLET_ID,"LoggingServlet(slf4j) initialized",null),
                new LogEvent(null,-1,Severity.INFO,LOGGING_SERVLET_ID,"LoggingServlet(slf4j) GET requested",null),
                new LogEvent(null,-1,Severity.DEBUG,LOGGING_SERVLET_ID,"LoggingServlet(commons-logging) initialized",null),
                new LogEvent(null,-1,Severity.INFO,LOGGING_SERVLET_ID,"LoggingServlet(commons-logging) GET requested",null),
                new LogEvent(null,-1,Severity.DEBUG,LOGGING_SERVLET_ID,"LoggingServlet(java) initialized",null),
                new LogEvent(null,-1,Severity.INFO,LOGGING_SERVLET_ID,"LoggingServlet(java) GET requested",null) };

        assertContainsLogEvents(testAppender,expectedLogs);
    }

    public void testEmbeddedWebappCommonsLogging() throws Exception
    {
        Server server = createWebAppServer("/clogging","dummy-webapp-logging-commons.war");

        server.start();

        SimpleRequest.get(server,"/clogging/logging");

        server.stop();

        TestAppender.LogEvent expectedLogs[] =
        { new LogEvent(null,-1,Severity.DEBUG,LOGGING_SERVLET_ID,"LoggingServlet(commons-logging) initialized",null),
                new LogEvent(null,-1,Severity.INFO,LOGGING_SERVLET_ID,"LoggingServlet(commons-logging) GET requested",null) };

        assertContainsLogEvents(testAppender,expectedLogs);
    }

    public void testEmbeddedWebappJavaUtil() throws Exception
    {
        Server server = createWebAppServer("/javalogging","dummy-webapp-logging-java.war");

        server.start();

        SimpleRequest.get(server,"/javalogging/logging");

        server.stop();

        TestAppender.LogEvent expectedLogs[] =
        { new LogEvent(null,-1,Severity.DEBUG,LOGGING_SERVLET_ID,"LoggingServlet(java) initialized",null),
                new LogEvent(null,-1,Severity.INFO,LOGGING_SERVLET_ID,"LoggingServlet(java) GET requested",null) };

        assertContainsLogEvents(testAppender,expectedLogs);
    }

    public void testEmbeddedWebappLog4j() throws Exception
    {
        Server server = createWebAppServer("/log4j","dummy-webapp-logging-log4j.war");

        server.start();

        SimpleRequest.get(server,"/log4j/logging");

        server.stop();

        TestAppender.LogEvent expectedLogs[] =
        { new LogEvent(null,-1,Severity.DEBUG,LOGGING_SERVLET_ID,"LoggingServlet(log4j) initialized",null),
                new LogEvent(null,-1,Severity.INFO,LOGGING_SERVLET_ID,"LoggingServlet(log4j) GET requested",null) };

        assertContainsLogEvents(testAppender,expectedLogs);
    }

    public void testEmbeddedWebappSlf4j() throws Exception
    {
        Server server = createWebAppServer("/slf4j","dummy-webapp-logging-slf4j.war");

        server.start();

        SimpleRequest.get(server,"/slf4j/logging");

        server.stop();

        TestAppender.LogEvent expectedLogs[] =
        { new LogEvent(null,-1,Severity.DEBUG,LOGGING_SERVLET_ID,"LoggingServlet(slf4j) initialized",null),
                new LogEvent(null,-1,Severity.INFO,LOGGING_SERVLET_ID,"LoggingServlet(slf4j) GET requested",null) };

        assertContainsLogEvents(testAppender,expectedLogs);
    }
}
