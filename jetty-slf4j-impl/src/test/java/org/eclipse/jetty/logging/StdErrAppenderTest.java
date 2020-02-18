//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.logging;

import java.util.Properties;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

import static java.time.ZoneOffset.UTC;

public class StdErrAppenderTest
{
    private JettyLogger logger = new JettyLogger("org.eclipse.jetty.logging.LogTest");

    @Test
    public void testStdErrLogFormat()
    {
        JettyLoggerConfiguration config = new JettyLoggerConfiguration();
        CapturedStream output = new CapturedStream();
        StdErrAppender appender = new StdErrAppender(config, output, UTC);

        String threadName = "tname";
        // Feb 17th, 2020 at 19:11:35 UTC (with 563 millis)
        long timestamp = 1581966695563L;

        JettyLoggingEvent event = new JettyLoggingEvent(logger, Level.INFO, threadName, timestamp, "testing:{},{}", null, "test", "format1");
        appender.emit(event);

        System.err.println(output);
        output.assertContains("2020-02-17 19:11:35.563:INFO:oejl.LogTest:tname: testing:test,format1");
    }

    @Test
    @Disabled("Needs org.slf4j.spi.LocationAwareLogger impl")
    public void testPrintSource()
    {
        Properties props = new Properties();
        props.put(logger.getName() + ".SOURCE", "true");

        JettyLoggerConfiguration config = new JettyLoggerConfiguration(props);
        CapturedStream output = new CapturedStream();
        StdErrAppender appender = new StdErrAppender(config, output, UTC);

        String threadName = "tname";
        // Feb 17th, 2020 at 19:11:35 UTC (with 563 millis)
        long timestamp = 1581966695563L;

        JettyLoggingEvent event = new JettyLoggingEvent(logger, Level.DEBUG, threadName, timestamp, "Show me the source!", null);
        appender.emit(event);

        System.err.println(output);
        output.assertContains(".StdErrAppenderTest#testPrintSource(StdErrAppenderTest.java:");
    }
}
