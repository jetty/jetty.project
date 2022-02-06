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

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Properties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.event.Level;

import static java.time.ZoneOffset.UTC;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Tests for JettyLogger and StdErrAppender
 */
public class JettyLoggerTest
{
    @BeforeEach
    public void before()
    {
        Thread.currentThread().setName("tname");
    }

    @SuppressWarnings("PlaceholderCountMatchesArgumentCount")
    @Test
    public void testStdErrLogFormatSlf4jStrict()
    {
        Properties props = new Properties();
        props.setProperty(StdErrAppender.ZONEID_KEY, UTC.getId());
        JettyLoggerConfiguration config = new JettyLoggerConfiguration(props);
        JettyLoggerFactory factory = new JettyLoggerFactory(config);

        StdErrAppender appender = (StdErrAppender)factory.getRootLogger().getAppender();
        CapturedStream output = new CapturedStream();
        appender.setStream(output);

        Logger log = factory.getJettyLogger(JettyLoggerTest.class.getName());

        log.info("testing:{},{}", "test", "format1");
        log.info("testing:{}", "test", "format2");
        log.info("testing", "test", "format3");
        log.info("testing:{},{}", "test", null);
        log.info("testing {} {}", null, null);
        log.info("testing:{}", null, null);
        log.info("testing", null, null);
        String msg = null;
        log.info(msg, "test2", "format4");

        System.err.println(output);
        output.assertContains("INFO :oejl.JettyLoggerTest:tname: testing:test,format1");
        output.assertContains("INFO :oejl.JettyLoggerTest:tname: testing:test");
        output.assertContains("INFO :oejl.JettyLoggerTest:tname: testing");
        output.assertContains("INFO :oejl.JettyLoggerTest:tname: testing:test,null");
        output.assertContains("INFO :oejl.JettyLoggerTest:tname: testing null null");
        output.assertContains("INFO :oejl.JettyLoggerTest:tname: testing:null");
        output.assertContains("INFO :oejl.JettyLoggerTest:tname: testing");
        output.assertContains("INFO :oejl.JettyLoggerTest:tname: ");
    }

    @Test
    public void testStdErrLogDebug()
    {
        JettyLoggerConfiguration config = new JettyLoggerConfiguration();
        JettyLoggerFactory factory = new JettyLoggerFactory(config);

        StdErrAppender appender = (StdErrAppender)factory.getRootLogger().getAppender();
        CapturedStream output = new CapturedStream();
        appender.setStream(output);

        JettyLogger log = factory.getJettyLogger("xxx");

        log.setLevel(JettyLevel.DEBUG);
        log.debug("testing {} {}", "test", "debug");
        log.info("testing {} {}", "test", "info");
        log.warn("testing {} {}", "test", "warn");
        log.setLevel(JettyLevel.INFO);
        log.debug("YOU SHOULD NOT SEE THIS!");

        output.assertContains("DEBUG:xxx:tname: testing test debug");
        output.assertContains("INFO :xxx:tname: testing test info");
        output.assertContains("WARN :xxx:tname: testing test warn");
        output.assertNotContains("YOU SHOULD NOT SEE THIS!");
    }

    @Test
    public void testStdErrLogName()
    {
        Properties props = new Properties();
        props.setProperty(StdErrAppender.NAME_CONDENSE_KEY, "false");
        JettyLoggerConfiguration config = new JettyLoggerConfiguration(props);
        JettyLoggerFactory factory = new JettyLoggerFactory(config);

        StdErrAppender appender = (StdErrAppender)factory.getRootLogger().getAppender();
        CapturedStream output = new CapturedStream();
        appender.setStream(output);

        JettyLogger log = factory.getJettyLogger("test");

        assertThat("Log.name", log.getName(), is("test"));
        Logger next = factory.getLogger(log.getName() + ".next");
        assertThat("Log.name(child)", next.getName(), is("test.next"));
        next.info("testing {} {}", "next", "info");

        output.assertContains(":test.next:tname: testing next info");
    }

    @Test
    public void testStdErrThrowable()
    {
        JettyLoggerConfiguration config = new JettyLoggerConfiguration();
        JettyLoggerFactory factory = new JettyLoggerFactory(config);

        StdErrAppender appender = (StdErrAppender)factory.getRootLogger().getAppender();
        CapturedStream output = new CapturedStream();
        appender.setStream(output);

        // Common Throwable (for test)
        Throwable th = new Throwable("Message");

        // Capture raw string form
        StringWriter tout = new StringWriter();
        th.printStackTrace(new PrintWriter(tout));
        String ths = tout.toString();

        // Start test
        JettyLogger log = factory.getJettyLogger("test");
        log.warn("ex", th);
        output.assertContains(ths);

        th = new Throwable("Message with \033 escape");

        log.warn("ex", th);
        output.assertNotContains("Message with \033 escape");
        log.info(th.toString());
        output.assertNotContains("Message with \033 escape");

        log.warn("ex", th);
        output.assertContains("Message with ? escape");
        log.info(th.toString());
        output.assertContains("Message with ? escape");
    }

    @Test
    public void testStdErrLogMessageAlignment()
    {
        Properties props = new Properties();
        props.setProperty(StdErrAppender.MESSAGE_ALIGN_KEY, "50");
        JettyLoggerConfiguration config = new JettyLoggerConfiguration(props);
        JettyLoggerFactory factory = new JettyLoggerFactory(config);

        StdErrAppender appender = (StdErrAppender)factory.getRootLogger().getAppender();
        CapturedStream output = new CapturedStream();
        appender.setStream(output);

        JettyLogger logJetty = factory.getJettyLogger("jetty");
        JettyLogger logJettyDeep = factory.getJettyLogger("jetty.from.deep");
        JettyLogger logJettyDeeper = factory.getJettyLogger("jetty.component.deeper.still");

        logJetty.setLevel(JettyLevel.DEBUG);
        logJettyDeep.debug("testing {} {}", "test", "debug");
        logJettyDeep.info("testing {} {}", "test", "info");
        logJettyDeep.warn("testing {} {}", "test", "warn");
        logJettyDeeper.debug("testing {} {}", "test", "debug");
        logJettyDeeper.info("testing {} {}", "test", "info");
        logJettyDeeper.warn("testing {} {}", "test", "warn");

        Thread.currentThread().setName("otherThread");
        logJetty.info("testing {} {}", "test", "info");
        logJettyDeep.info("testing {} {}", "test", "info");
        logJettyDeeper.info("testing {} {}", "test", "info");

        Thread.currentThread().setName("veryLongThreadName");
        logJetty.info("testing {} {}", "test", "info");
        logJettyDeep.info("testing {} {}", "test", "info");
        logJettyDeeper.info("testing {} {}", "test", "info");

        output.assertContains("DEBUG:jf.deep:tname:      testing test debug");
        output.assertContains("INFO :jf.deep:tname:      testing test info");
        output.assertContains("WARN :jf.deep:tname:      testing test warn");

        output.assertContains("DEBUG:jcd.still:tname:    testing test debug");
        output.assertContains("INFO :jcd.still:tname:    testing test info");
        output.assertContains("WARN :jcd.still:tname:    testing test warn");

        output.assertContains("INFO :jetty:otherThread:  testing test info");
        output.assertContains("INFO :jf.deep:otherThread: testing test info");
        output.assertContains("INFO :jcd.still:otherThread: testing test info");

        output.assertContains("INFO :jetty:veryLongThreadName: testing test info");
        output.assertContains("INFO :jf.deep:veryLongThreadName: testing test info");
        output.assertContains("INFO :jcd.still:veryLongThreadName: testing test info");
    }

    /**
     * Test to make sure that using a Null parameter on parameterized messages does not result in a NPE
     */
    @SuppressWarnings({"PlaceholderCountMatchesArgumentCount", "ConstantConditions"})
    @Test
    public void testParameterizedMessageNullValues()
    {
        JettyLoggerConfiguration config = new JettyLoggerConfiguration();
        JettyLoggerFactory factory = new JettyLoggerFactory(config);

        StdErrAppender appender = (StdErrAppender)factory.getRootLogger().getAppender();
        CapturedStream output = new CapturedStream();
        appender.setStream(output);

        JettyLogger log = factory.getJettyLogger(JettyLoggerTest.class.getName());
        log.setLevel(JettyLevel.DEBUG);

        String nullMsg = null;
        try (StacklessLogging ignored = new StacklessLogging(log))
        {
            log.info("Testing info(msg,null,null) - {} {}", "arg0", "arg1");
            log.info("Testing info(msg,null,null) - {} {}", null, null);
            log.info("Testing info(msg,null,null) - {}", null, null);
            log.info("Testing info(msg,null,null)", null, null);
            log.info(nullMsg, "Testing", "info(null,arg0,arg1)");
            log.info(nullMsg, null, null);

            log.debug("Testing debug(msg,null,null) - {} {}", "arg0", "arg1");
            log.debug("Testing debug(msg,null,null) - {} {}", null, null);
            log.debug("Testing debug(msg,null,null) - {}", null, null);
            log.debug("Testing debug(msg,null,null)", null, null);
            log.debug(nullMsg, "Testing", "debug(null,arg0,arg1)");
            log.debug(nullMsg, null, null);

            log.debug("Testing debug(msg,null)");
            log.debug(null, new Throwable("Testing debug(null,thrw)").fillInStackTrace());

            log.warn("Testing warn(msg,null,null) - {} {}", "arg0", "arg1");
            log.warn("Testing warn(msg,null,null) - {} {}", null, null);
            log.warn("Testing warn(msg,null,null) - {}", null, null);
            log.warn("Testing warn(msg,null,null)", null, null);
            log.warn(nullMsg, "Testing", "warn(msg,arg0,arg1)");
            log.warn(nullMsg, null, null);

            log.warn("Testing warn(msg,null)");
            log.warn(nullMsg, new Throwable("Testing warn(msg,thrw)").fillInStackTrace());
        }
    }

    /**
     * Tests JettyLogger.warn() methods with level filtering.
     * <p>
     * Should see WARN level messages, if level is set to WARN or below
     */
    @Test
    public void testWarnFiltering()
    {
        JettyLoggerConfiguration config = new JettyLoggerConfiguration();
        JettyLoggerFactory factory = new JettyLoggerFactory(config);

        StdErrAppender appender = (StdErrAppender)factory.getRootLogger().getAppender();
        CapturedStream output = new CapturedStream();
        appender.setStream(output);

        JettyLogger log = factory.getJettyLogger(JettyLoggerTest.class.getName());

        try (StacklessLogging ignored = new StacklessLogging(log))
        {
            // Start with default level
            log.warn("See Me");

            // Set to debug level
            log.setLevel(JettyLevel.DEBUG);
            log.warn("Hear Me");

            // Set to warn level
            log.setLevel(JettyLevel.WARN);
            log.warn("Cheer Me");

            log.warn("<zoom>", new Throwable("out of focus"));
            log.warn("shot issue", new Throwable("scene lost"));

            // Validate Output
            // System.err.print(output);
            output.assertContains("See Me");
            output.assertContains("Hear Me");
            output.assertContains("Cheer Me");

            // Validate Stack Traces
            output.assertContains(".JettyLoggerTest:tname: <zoom>");
            output.assertContains("java.lang.Throwable: out of focus");
            output.assertContains("java.lang.Throwable: scene lost");
        }
    }

    /**
     * Tests JettyLogger.info() methods with level filtering.
     * <p>
     * Should only see INFO level messages when level is set to {@link Level#INFO} and below.
     */
    @Test
    public void testInfoFiltering()
    {
        JettyLoggerConfiguration config = new JettyLoggerConfiguration();
        JettyLoggerFactory factory = new JettyLoggerFactory(config);

        StdErrAppender appender = (StdErrAppender)factory.getRootLogger().getAppender();
        CapturedStream output = new CapturedStream();
        appender.setStream(output);

        JettyLogger log = factory.getJettyLogger(JettyLoggerTest.class.getName());
        try (StacklessLogging ignored = new StacklessLogging(log))
        {
            // Normal/Default behavior
            log.info("I will not buy");

            // Level Debug
            log.setLevel(JettyLevel.DEBUG);
            log.info("this record");

            // Level All
            log.setLevel(JettyLevel.TRACE);
            log.info("it is scratched.");

            log.info("<zoom>", new Throwable("out of focus"));
            log.info("shot issue", new Throwable("scene lost"));

            // Level Warn
            log.setLevel(JettyLevel.WARN);
            log.info("sorry?");
            log.info("<spoken line>", new Throwable("on editing room floor"));

            // Validate Output
            output.assertContains("I will not buy");
            output.assertContains("this record");
            output.assertContains("it is scratched.");
            output.assertNotContains("sorry?");

            // Validate Stack Traces
            output.assertNotContains("<spoken line>");
            output.assertNotContains("on editing room floor");

            output.assertContains(".JettyLoggerTest:tname: <zoom>");
            output.assertContains("java.lang.Throwable: out of focus");
            output.assertContains("java.lang.Throwable: scene lost");
        }
    }

    /**
     * Tests {@link Level#ERROR} filtering.
     */
    @Test
    public void testErrorFiltering()
    {
        JettyLoggerConfiguration config = new JettyLoggerConfiguration();
        JettyLoggerFactory factory = new JettyLoggerFactory(config);

        StdErrAppender appender = (StdErrAppender)factory.getRootLogger().getAppender();
        CapturedStream output = new CapturedStream();
        appender.setStream(output);

        JettyLogger log = factory.getJettyLogger(JettyLoggerTest.class.getName());
        try (StacklessLogging ignored = new StacklessLogging(log))
        {
            log.setLevel(JettyLevel.ERROR);

            // Various logging events
            log.debug("Squelch");
            log.debug("Squelch", new RuntimeException("Squelch"));
            log.info("Squelch");
            log.info("Squelch", new IllegalStateException("Squelch"));
            log.warn("Squelch");
            log.warn("Squelch", new Exception("Squelch"));
            log.trace("IGNORED", new Throwable("Squelch"));

            // Validate Output
            output.assertNotContains("Squelch");
        }
    }

    /**
     * Tests level OFF filtering.
     */
    @Test
    public void testOffFiltering()
    {
        JettyLoggerConfiguration config = new JettyLoggerConfiguration();
        JettyLoggerFactory factory = new JettyLoggerFactory(config);

        StdErrAppender appender = (StdErrAppender)factory.getRootLogger().getAppender();
        CapturedStream output = new CapturedStream();
        appender.setStream(output);

        JettyLogger log = factory.getJettyLogger(JettyLoggerTest.class.getName());

        try (StacklessLogging ignored = new StacklessLogging(log))
        {
            log.setLevel(JettyLevel.OFF);

            // Various logging events
            log.debug("Squelch");
            log.debug("Squelch", new RuntimeException("Squelch"));
            log.info("Squelch");
            log.info("Squelch", new IllegalStateException("Squelch"));
            log.warn("Squelch");
            log.warn("Squelch", new Exception("Squelch"));
            log.trace("IGNORED", new Throwable("Squelch"));

            // Validate Output
            output.assertNotContains("Squelch");
        }
    }

    /**
     * Tests StdErrLog.debug() methods with level filtering.
     * <p>
     * Should only see DEBUG level messages when level is set to {@link Level#DEBUG} and below.
     */
    @Test
    public void testDebugFiltering()
    {
        JettyLoggerConfiguration config = new JettyLoggerConfiguration();
        JettyLoggerFactory factory = new JettyLoggerFactory(config);

        StdErrAppender appender = (StdErrAppender)factory.getRootLogger().getAppender();
        CapturedStream output = new CapturedStream();
        appender.setStream(output);

        JettyLogger log = factory.getJettyLogger(JettyLoggerTest.class.getName());

        try (StacklessLogging ignored = new StacklessLogging(log))
        {
            // Normal/Default behavior
            log.debug("Tobacconist");
            log.debug("<spoken line>", new Throwable("on editing room floor"));

            // Level Debug
            log.setLevel(JettyLevel.DEBUG);
            log.debug("my hovercraft is");

            log.debug("<zoom>", new Throwable("out of focus"));
            log.debug("shot issue", new Throwable("scene lost"));

            // Level All
            log.setLevel(JettyLevel.TRACE);
            log.debug("full of eels.");

            // Level Warn
            log.setLevel(JettyLevel.WARN);
            log.debug("what?");

            // Validate Output
            // System.err.print(output);
            output.assertNotContains("Tobacconist");
            output.assertContains("my hovercraft is");
            output.assertContains("full of eels.");
            output.assertNotContains("what?");

            // Validate Stack Traces
            output.assertNotContains("<spoken line>");
            output.assertNotContains("on editing room floor");

            output.assertContains(".JettyLoggerTest:tname: <zoom>");
            output.assertContains("java.lang.Throwable: out of focus");
            output.assertContains("java.lang.Throwable: scene lost");
        }
    }

    @Test
    public void testIsDebugEnabled()
    {
        JettyLoggerConfiguration config = new JettyLoggerConfiguration();
        JettyLoggerFactory factory = new JettyLoggerFactory(config);

        StdErrAppender appender = (StdErrAppender)factory.getRootLogger().getAppender();
        CapturedStream output = new CapturedStream();
        appender.setStream(output);

        JettyLogger log = factory.getJettyLogger(JettyLoggerTest.class.getName());

        try (StacklessLogging ignored = new StacklessLogging(log))
        {
            log.setLevel(JettyLevel.TRACE);
            assertThat("log.level(trace).isDebugEnabled", log.isDebugEnabled(), is(true));

            log.setLevel(JettyLevel.DEBUG);
            assertThat("log.level(debug).isDebugEnabled", log.isDebugEnabled(), is(true));

            log.setLevel(JettyLevel.INFO);
            assertThat("log.level(info).isDebugEnabled", log.isDebugEnabled(), is(false));

            log.setLevel(JettyLevel.WARN);
            assertThat("log.level(warn).isDebugEnabled", log.isDebugEnabled(), is(false));

            log.setLevel(JettyLevel.ERROR);
            assertThat("log.level(error).isDebugEnabled", log.isDebugEnabled(), is(false));

            log.setLevel(JettyLevel.OFF);
            assertThat("log.level(null).isDebugEnabled", log.isDebugEnabled(), is(false));
        }
    }

    @Test
    public void testSetGetLevel()
    {
        JettyLoggerConfiguration config = new JettyLoggerConfiguration();
        JettyLoggerFactory factory = new JettyLoggerFactory(config);

        StdErrAppender appender = (StdErrAppender)factory.getRootLogger().getAppender();
        CapturedStream output = new CapturedStream();
        appender.setStream(output);

        JettyLogger log = factory.getJettyLogger(JettyLoggerTest.class.getName());

        try (StacklessLogging ignored = new StacklessLogging(log))
        {
            log.setLevel(JettyLevel.TRACE);
            assertThat("log.level(trace).getLevel()", log.getLevel(), is(JettyLevel.TRACE));

            log.setLevel(JettyLevel.DEBUG);
            assertThat("log.level(debug).getLevel()", log.getLevel(), is(JettyLevel.DEBUG));

            log.setLevel(JettyLevel.INFO);
            assertThat("log.level(info).getLevel()", log.getLevel(), is(JettyLevel.INFO));

            log.setLevel(JettyLevel.WARN);
            assertThat("log.level(warn).getLevel()", log.getLevel(), is(JettyLevel.WARN));

            log.setLevel(JettyLevel.ERROR);
            assertThat("log.level(error).getLevel()", log.getLevel(), is(JettyLevel.ERROR));

            log.setLevel(JettyLevel.OFF);
            assertThat("log.level(off).getLevel()", log.getLevel(), is(JettyLevel.OFF));
        }
    }

    @Test
    public void testToString()
    {
        JettyLoggerConfiguration config = new JettyLoggerConfiguration();
        JettyLoggerFactory factory = new JettyLoggerFactory(config);

        StdErrAppender appender = (StdErrAppender)factory.getRootLogger().getAppender();
        CapturedStream output = new CapturedStream();
        appender.setStream(output);

        JettyLogger log = factory.getJettyLogger("xxx");

        log.setLevel(JettyLevel.TRACE);
        assertThat("Logger.toString", log.toString(), is("JettyLogger:xxx:LEVEL=TRACE"));

        log.setLevel(JettyLevel.DEBUG);
        assertThat("Logger.toString", log.toString(), is("JettyLogger:xxx:LEVEL=DEBUG"));

        log.setLevel(JettyLevel.INFO);
        assertThat("Logger.toString", log.toString(), is("JettyLogger:xxx:LEVEL=INFO"));

        log.setLevel(JettyLevel.WARN);
        assertThat("Logger.toString", log.toString(), is("JettyLogger:xxx:LEVEL=WARN"));

        log.setLevel(JettyLevel.ERROR);
        assertThat("Logger.toString", log.toString(), is("JettyLogger:xxx:LEVEL=ERROR"));

        log.setLevel(JettyLevel.OFF);
        assertThat("Logger.toString", log.toString(), is("JettyLogger:xxx:LEVEL=OFF"));
    }

    @Test
    public void testConfiguredAndSetDebugEnabled()
    {
        Properties props = new Properties();
        props.setProperty("org.eclipse.jetty.util.LEVEL", "WARN");
        props.setProperty("org.eclipse.jetty.io.LEVEL", "WARN");

        JettyLoggerConfiguration config = new JettyLoggerConfiguration(props);
        JettyLoggerFactory factory = new JettyLoggerFactory(config);

        StdErrAppender appender = (StdErrAppender)factory.getRootLogger().getAppender();
        CapturedStream output = new CapturedStream();
        appender.setStream(output);

        JettyLogger root = factory.getJettyLogger("");
        assertLevel(root, JettyLevel.INFO); // default

        JettyLogger log = factory.getJettyLogger("org.eclipse.jetty.util.Foo");
        assertThat("Log.isDebugEnabled()", log.isDebugEnabled(), is(false));
        assertLevel(log, JettyLevel.WARN); // as configured

        // Boot stomp it all to debug
        root.setLevel(JettyLevel.DEBUG);
        assertThat("Log.isDebugEnabled()", log.isDebugEnabled(), is(true));
        assertLevel(log, JettyLevel.DEBUG); // as stomped

        // Restore configured
        factory.walkChildrenLoggers(root.getName(), (logger) ->
        {
            JettyLevel configuredLevel = config.getLevel(logger.getName());
            logger.setLevel(configuredLevel);
        });
        assertThat("Log.isDebugEnabled()", log.isDebugEnabled(), is(false));
        assertLevel(log, JettyLevel.WARN); // as configured
    }

    @Test
    public void testSuppressed()
    {
        JettyLoggerConfiguration config = new JettyLoggerConfiguration();
        JettyLoggerFactory factory = new JettyLoggerFactory(config);

        StdErrAppender appender = (StdErrAppender)factory.getRootLogger().getAppender();
        CapturedStream output = new CapturedStream();
        appender.setStream(output);

        JettyLogger log = factory.getJettyLogger("xxx");

        Exception inner = new Exception("inner");
        inner.addSuppressed(new IllegalStateException()
        {
            {
                addSuppressed(new Exception("branch0"));
            }
        });
        IOException outer = new IOException("outer", inner);

        outer.addSuppressed(new IllegalStateException()
        {
            {
                addSuppressed(new Exception("branch1"));
            }
        });
        outer.addSuppressed(new IllegalArgumentException()
        {
            {
                addSuppressed(new Exception("branch2"));
            }
        });

        log.warn("problem", outer);

        output.assertContains("\t|\t|java.lang.Exception: branch2");
        output.assertContains("\t|\t|java.lang.Exception: branch1");
        output.assertContains("\t|\t|java.lang.Exception: branch0");
    }

    private void assertLevel(JettyLogger log, JettyLevel expectedLevel)
    {
        assertThat("Log[" + log.getName() + "].level",
            log.getLevel(), is(expectedLevel));
    }
}
