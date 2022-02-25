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

package org.eclipse.jetty.logging;

import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

public class StdErrAppenderTest
{
    @Test
    public void testStdErrLogFormat()
    {
        Properties props = new Properties();
        props.setProperty(StdErrAppender.ZONEID_KEY, "UTC");
        JettyLoggerConfiguration config = new JettyLoggerConfiguration(props);
        CapturedStream output = new CapturedStream();
        JettyLoggerFactory factory = new JettyLoggerFactory(config);
        StdErrAppender appender = (StdErrAppender)factory.getRootLogger().getAppender();
        appender.setStream(output);
        JettyLogger logger = factory.getJettyLogger("org.eclipse.jetty.logging.LogTest");

        String threadName = "tname";
        // Feb 17th, 2020 at 19:11:35 UTC (with 563 millis)
        long timestamp = 1581966695563L;

        appender.emit(logger, Level.INFO, timestamp, threadName, null, "testing:{},{}", "test", "format1");

        System.err.println(output);
        output.assertContains("2020-02-17 19:11:35.563:INFO :oejl.LogTest:tname: testing:test,format1");
    }
}
