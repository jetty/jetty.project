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
package org.eclipse.jetty.webapp.logging;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;

import junit.framework.TestCase;

import org.eclipse.jetty.logging.impl.CentralLoggerConfig;
import org.eclipse.jetty.logging.impl.Severity;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.webapp.Configuration;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.webapp.logging.TestAppender.LogEvent;

/**
 * Test centralized logging in an embedded scenario
 */
public class EmbeddedCentralizedLoggingTest extends TestCase
{
    private static final String LOGGING_SERVLET_ID = "org.eclipse.jetty.tests.webapp.LoggingServlet";
    private TestAppender testAppender;

    private void assertContainsLogEvents(TestAppender capturedEvents, List<LogEvent> expectedLogs)
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

        String prefix = "LoggingServlet(commons-logging)";
        List<LogEvent> expectedLogs = new ArrayList<LogEvent>();
        // expectedLogs.add(new LogEvent(Severity.DEBUG,LOGGING_SERVLET_ID,prefix + " initialized"));
        expectedLogs.add(new LogEvent(Severity.INFO,LOGGING_SERVLET_ID,prefix + " GET requested"));
        expectedLogs.add(new LogEvent(Severity.WARN,LOGGING_SERVLET_ID,prefix + " Slightly warn, with a chance of log events"));
        expectedLogs.add(new LogEvent(Severity.ERROR,LOGGING_SERVLET_ID,prefix + " Nothing is (intentionally) being output by this Servlet"));
        expectedLogs.add(new LogEvent(Severity.ERROR,LOGGING_SERVLET_ID,prefix + " Whoops (intentionally) causing a Throwable")
                .expectedThrowable(new FileNotFoundException("A file cannot be found")));
        prefix = "LoggingServlet(log4j)";
        // expectedLogs.add(new LogEvent(Severity.DEBUG,LOGGING_SERVLET_ID,prefix + " initialized"));
        expectedLogs.add(new LogEvent(Severity.INFO,LOGGING_SERVLET_ID,prefix + " GET requested"));
        expectedLogs.add(new LogEvent(Severity.WARN,LOGGING_SERVLET_ID,prefix + " Slightly warn, with a chance of log events"));
        expectedLogs.add(new LogEvent(Severity.ERROR,LOGGING_SERVLET_ID,prefix + " Nothing is (intentionally) being output by this Servlet"));
        expectedLogs.add(new LogEvent(Severity.ERROR,LOGGING_SERVLET_ID,prefix + " Whoops (intentionally) causing a Throwable")
                .expectedThrowable(new FileNotFoundException("A file cannot be found")));
        prefix = "LoggingServlet(java)";
        // expectedLogs.add(new LogEvent(Severity.DEBUG,LOGGING_SERVLET_ID,prefix + " initialized"));
        expectedLogs.add(new LogEvent(Severity.INFO,LOGGING_SERVLET_ID,prefix + " GET requested"));
        expectedLogs.add(new LogEvent(Severity.WARN,LOGGING_SERVLET_ID,prefix + " Slightly warn, with a chance of log events"));
        expectedLogs.add(new LogEvent(Severity.WARN,LOGGING_SERVLET_ID,prefix + " Nothing is (intentionally) being output by this Servlet"));
        expectedLogs.add(new LogEvent(Severity.ERROR,LOGGING_SERVLET_ID,prefix + " Whoops (intentionally) causing a Throwable")
                .expectedThrowable(new FileNotFoundException("A file cannot be found")));
        prefix = "LoggingServlet(slf4j)";
        // expectedLogs.add(new LogEvent(Severity.DEBUG,LOGGING_SERVLET_ID,prefix + " initialized"));
        expectedLogs.add(new LogEvent(Severity.INFO,LOGGING_SERVLET_ID,prefix + " GET requested"));
        expectedLogs.add(new LogEvent(Severity.WARN,LOGGING_SERVLET_ID,prefix + " Slightly warn, with a chance of log events"));
        expectedLogs.add(new LogEvent(Severity.ERROR,LOGGING_SERVLET_ID,prefix + " Nothing is (intentionally) being output by this Servlet"));
        expectedLogs.add(new LogEvent(Severity.ERROR,LOGGING_SERVLET_ID,prefix + " Whoops (intentionally) causing a Throwable")
                .expectedThrowable(new FileNotFoundException("A file cannot be found")));

        assertContainsLogEvents(testAppender,expectedLogs);
    }

    public void testEmbeddedWebappCommonsLogging() throws Exception
    {
        Server server = createWebAppServer("/clogging","dummy-webapp-logging-commons.war");

        server.start();

        SimpleRequest.get(server,"/clogging/logging");

        server.stop();

        String prefix = "LoggingServlet(commons-logging)";
        List<LogEvent> expectedLogs = new ArrayList<LogEvent>();
        // expectedLogs.add(new LogEvent(Severity.DEBUG,LOGGING_SERVLET_ID,prefix + " initialized"));
        expectedLogs.add(new LogEvent(Severity.INFO,LOGGING_SERVLET_ID,prefix + " GET requested"));
        expectedLogs.add(new LogEvent(Severity.WARN,LOGGING_SERVLET_ID,prefix + " Slightly warn, with a chance of log events"));
        expectedLogs.add(new LogEvent(Severity.ERROR,LOGGING_SERVLET_ID,prefix + " Nothing is (intentionally) being output by this Servlet"));
        expectedLogs.add(new LogEvent(Severity.ERROR,LOGGING_SERVLET_ID,prefix + " Whoops (intentionally) causing a Throwable")
                .expectedThrowable(new FileNotFoundException("A file cannot be found")));

        assertContainsLogEvents(testAppender,expectedLogs);
    }

    public void testEmbeddedWebappJavaUtil() throws Exception
    {
        Server server = createWebAppServer("/javalogging","dummy-webapp-logging-java.war");

        server.start();

        SimpleRequest.get(server,"/javalogging/logging");

        server.stop();

        String prefix = "LoggingServlet(java)";
        List<LogEvent> expectedLogs = new ArrayList<LogEvent>();
        // expectedLogs.add(new LogEvent(Severity.DEBUG,LOGGING_SERVLET_ID,prefix + " initialized"));
        expectedLogs.add(new LogEvent(Severity.INFO,LOGGING_SERVLET_ID,prefix + " GET requested"));
        expectedLogs.add(new LogEvent(Severity.WARN,LOGGING_SERVLET_ID,prefix + " Slightly warn, with a chance of log events"));
        expectedLogs.add(new LogEvent(Severity.WARN,LOGGING_SERVLET_ID,prefix + " Nothing is (intentionally) being output by this Servlet"));
        expectedLogs.add(new LogEvent(Severity.ERROR,LOGGING_SERVLET_ID,prefix + " Whoops (intentionally) causing a Throwable")
                .expectedThrowable(new FileNotFoundException("A file cannot be found")));

        assertContainsLogEvents(testAppender,expectedLogs);
    }

    public void testEmbeddedWebappLog4j() throws Exception
    {
        Server server = createWebAppServer("/log4j","dummy-webapp-logging-log4j.war");

        server.start();

        SimpleRequest.get(server,"/log4j/logging");

        server.stop();

        String prefix = "LoggingServlet(log4j)";
        List<LogEvent> expectedLogs = new ArrayList<LogEvent>();
        // expectedLogs.add(new LogEvent(Severity.DEBUG,LOGGING_SERVLET_ID,prefix + " initialized"));
        expectedLogs.add(new LogEvent(Severity.INFO,LOGGING_SERVLET_ID,prefix + " GET requested"));
        expectedLogs.add(new LogEvent(Severity.WARN,LOGGING_SERVLET_ID,prefix + " Slightly warn, with a chance of log events"));
        expectedLogs.add(new LogEvent(Severity.ERROR,LOGGING_SERVLET_ID,prefix + " Nothing is (intentionally) being output by this Servlet"));
        expectedLogs.add(new LogEvent(Severity.ERROR,LOGGING_SERVLET_ID,prefix + " Whoops (intentionally) causing a Throwable")
                .expectedThrowable(new FileNotFoundException("A file cannot be found")));

        assertContainsLogEvents(testAppender,expectedLogs);
    }

    public void testEmbeddedWebappSlf4j() throws Exception
    {
        Server server = createWebAppServer("/slf4j","dummy-webapp-logging-slf4j.war");

        server.start();

        SimpleRequest.get(server,"/slf4j/logging");

        server.stop();

        String prefix = "LoggingServlet(slf4j)";
        List<LogEvent> expectedLogs = new ArrayList<LogEvent>();
        // expectedLogs.add(new LogEvent(Severity.DEBUG,LOGGING_SERVLET_ID,prefix + " initialized"));
        expectedLogs.add(new LogEvent(Severity.INFO,LOGGING_SERVLET_ID,prefix + " GET requested"));
        expectedLogs.add(new LogEvent(Severity.WARN,LOGGING_SERVLET_ID,prefix + " Slightly warn, with a chance of log events"));
        expectedLogs.add(new LogEvent(Severity.ERROR,LOGGING_SERVLET_ID,prefix + " Nothing is (intentionally) being output by this Servlet"));
        expectedLogs.add(new LogEvent(Severity.ERROR,LOGGING_SERVLET_ID,prefix + " Whoops (intentionally) causing a Throwable")
                .expectedThrowable(new FileNotFoundException("A file cannot be found")));

        assertContainsLogEvents(testAppender,expectedLogs);
    }
}
