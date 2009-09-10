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

import java.io.IOException;

import junit.framework.TestCase;

import org.eclipse.jetty.logging.impl.CentralLoggerConfig;
import org.eclipse.jetty.logging.impl.Severity;
import org.eclipse.jetty.logging.impl.TestAppender;
import org.eclipse.jetty.logging.impl.TestAppender.LogEvent;

public class CentralizedLoggingTest extends TestCase
{
    private static final String LOGGING_SERVLET_ID = "org.eclipse.jetty.tests.webapp.LoggingServlet";
    private XmlConfiguredJetty jetty;

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

    @Override
    protected void setUp() throws Exception
    {
        super.setUp();

        jetty = new XmlConfiguredJetty(this);
        jetty.addConfiguration("jetty.xml");
        jetty.addConfiguration("jetty-centralized-logging.xml");

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

        boolean isSurefireExecuting = MavenTestingUtils.isSurefireExecuting();

        // HACK: causes failures within surefire.
        if (!isSurefireExecuting)
        {
            SimpleRequest.get(jetty,"/dummy-webapp-logging-log4j/logging");
            SimpleRequest.get(jetty,"/dummy-webapp-logging-commons/logging");
        }

        SimpleRequest.get(jetty,"/dummy-webapp-logging-slf4j/logging");
        SimpleRequest.get(jetty,"/dummy-webapp-logging-java/logging");

        TestAppender.LogEvent expectedLogs[];
        if (isSurefireExecuting)
        {
            expectedLogs = new LogEvent[]
            { new LogEvent(null,-1,Severity.DEBUG,LOGGING_SERVLET_ID,"LoggingServlet(slf4j) initialized",null),
                    new LogEvent(null,-1,Severity.INFO,LOGGING_SERVLET_ID,"LoggingServlet(slf4j) GET requested",null),
                    new LogEvent(null,-1,Severity.DEBUG,LOGGING_SERVLET_ID,"LoggingServlet(java) initialized",null),
                    new LogEvent(null,-1,Severity.INFO,LOGGING_SERVLET_ID,"LoggingServlet(java) GET requested",null) };
        }
        else
        {
            expectedLogs = new LogEvent[]
            { new LogEvent(null,-1,Severity.DEBUG,LOGGING_SERVLET_ID,"LoggingServlet(log4j) initialized",null),
                    new LogEvent(null,-1,Severity.INFO,LOGGING_SERVLET_ID,"LoggingServlet(log4j) GET requested",null),
                    new LogEvent(null,-1,Severity.DEBUG,LOGGING_SERVLET_ID,"LoggingServlet(slf4j) initialized",null),
                    new LogEvent(null,-1,Severity.INFO,LOGGING_SERVLET_ID,"LoggingServlet(slf4j) GET requested",null),
                    new LogEvent(null,-1,Severity.DEBUG,LOGGING_SERVLET_ID,"LoggingServlet(commons-logging) initialized",null),
                    new LogEvent(null,-1,Severity.INFO,LOGGING_SERVLET_ID,"LoggingServlet(commons-logging) GET requested",null),
                    new LogEvent(null,-1,Severity.DEBUG,LOGGING_SERVLET_ID,"LoggingServlet(java) initialized",null),
                    new LogEvent(null,-1,Severity.INFO,LOGGING_SERVLET_ID,"LoggingServlet(java) GET requested",null) };
        }

        assertContainsLogEvents(testAppender,expectedLogs);
    }
}
