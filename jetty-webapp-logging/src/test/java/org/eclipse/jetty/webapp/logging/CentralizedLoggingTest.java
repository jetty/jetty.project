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

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import junit.framework.TestCase;

import org.eclipse.jetty.logging.impl.CentralLoggerConfig;
import org.eclipse.jetty.logging.impl.Severity;
import org.eclipse.jetty.webapp.logging.TestAppender.LogEvent;

public class CentralizedLoggingTest extends TestCase
{
    private static final String LOGGING_SERVLET_ID = "org.eclipse.jetty.tests.webapp.LoggingServlet";
    private XmlConfiguredJetty jetty;

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

    @Override
    protected void setUp() throws Exception
    {
        super.setUp();

        jetty = new XmlConfiguredJetty(this);
        jetty.addConfiguration("jetty.xml");
        jetty.addConfiguration("jetty-webapp-logging.xml");

        jetty.load();

        jetty.start();
    }

    @Override
    protected void tearDown() throws Exception
    {
        jetty.stop();

        super.tearDown();
    }

    public void testAllRouting() throws IOException
    {
        CentralLoggerConfig root = CentralizedWebAppLoggingConfiguration.getLoggerRoot();
        TestAppender testAppender = (TestAppender)root.findAppender(TestAppender.class);
        assertNotNull("Should have found TestAppender in configuration",testAppender);

        SimpleRequest.get(jetty,"/dummy-webapp-logging-log4j/logging");
        SimpleRequest.get(jetty,"/dummy-webapp-logging-commons/logging");
        SimpleRequest.get(jetty,"/dummy-webapp-logging-slf4j/logging");
        SimpleRequest.get(jetty,"/dummy-webapp-logging-java/logging");

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

        assertContainsLogEvents(testAppender,expectedLogs);
    }
}
