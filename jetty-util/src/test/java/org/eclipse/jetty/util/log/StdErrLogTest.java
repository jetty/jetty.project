//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.util.log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Tests for StdErrLog
 */
public class StdErrLogTest
{
    static
    {
        StdErrLog.setTagPad(0);
    }

    @BeforeEach
    public void before()
    {
        Thread.currentThread().setName("tname");
    }

    @Test
    public void testStdErrLogFormat()
    {
        StdErrLog log = new StdErrLog(LogTest.class.getName(), new Properties());
        StdErrCapture output = new StdErrCapture(log);

        log.info("testing:{},{}", "test", "format1");
        log.info("testing:{}", "test", "format2");
        log.info("testing", "test", "format3");
        log.info("testing:{},{}", "test", null);
        log.info("testing {} {}", null, null);
        log.info("testing:{}", null, null);
        log.info("testing", null, null);

        System.err.println(output);
        output.assertContains("INFO:oejul.LogTest:tname: testing:test,format1");
        output.assertContains("INFO:oejul.LogTest:tname: testing:test format2");
        output.assertContains("INFO:oejul.LogTest:tname: testing test format3");
        output.assertContains("INFO:oejul.LogTest:tname: testing:test,");
        output.assertContains("INFO:oejul.LogTest:tname: testing");
        output.assertContains("INFO:oejul.LogTest:tname: testing:");
        output.assertContains("INFO:oejul.LogTest:tname: testing");
    }

    @Test
    public void testStdErrLogDebug()
    {
        StdErrLog log = new StdErrLog("xxx", new Properties());
        StdErrCapture output = new StdErrCapture(log);

        log.setLevel(StdErrLog.LEVEL_DEBUG);
        log.debug("testing {} {}", "test", "debug");
        log.info("testing {} {}", "test", "info");
        log.warn("testing {} {}", "test", "warn");
        log.setLevel(StdErrLog.LEVEL_INFO);
        log.debug("YOU SHOULD NOT SEE THIS!", null, null);

        // Test for backward compat with old (now deprecated) method
        Logger before = log.getLogger("before");
        log.setDebugEnabled(true);
        Logger after = log.getLogger("after");
        before.debug("testing {} {}", "test", "debug-before");
        log.debug("testing {} {}", "test", "debug-deprecated");
        after.debug("testing {} {}", "test", "debug-after");

        log.setDebugEnabled(false);
        before.debug("testing {} {}", "test", "debug-before-false");
        log.debug("testing {} {}", "test", "debug-deprecated-false");
        after.debug("testing {} {}", "test", "debug-after-false");

        output.assertContains("DBUG:xxx:tname: testing test debug");
        output.assertContains("INFO:xxx:tname: testing test info");
        output.assertContains("WARN:xxx:tname: testing test warn");
        output.assertNotContains("YOU SHOULD NOT SEE THIS!");
        output.assertContains("DBUG:x.before:tname: testing test debug-before");
        output.assertContains("DBUG:xxx:tname: testing test debug-deprecated");
        output.assertContains("DBUG:x.after:tname: testing test debug-after");
        output.assertNotContains("DBUG:x.before:tname: testing test debug-before-false");
        output.assertNotContains("DBUG:xxx:tname: testing test debug-deprecated-false");
        output.assertNotContains("DBUG:x.after:tname: testing test debug-after-false");
    }

    @Test
    public void testStdErrLogName()
    {
        StdErrLog log = new StdErrLog("testX", new Properties());
        log.setPrintLongNames(true);
        StdErrCapture output = new StdErrCapture(log);

        assertThat("Log.name", log.getName(), is("testX"));
        Logger next = log.getLogger("next");
        assertThat("Log.name(child)", next.getName(), is("testX.next"));
        next.info("testing {} {}", "next", "info");

        output.assertContains(":testX.next:tname: testing next info");
    }

    @Test
    public void testStdErrMsgThrowable()
    {
        // The test Throwable
        Throwable th = new Throwable("Message");

        // Initialize Logger
        StdErrLog log = new StdErrLog("testX", new Properties());
        StdErrCapture output = new StdErrCapture(log);

        // Test behavior
        log.warn("ex", th); // Behavior here is being tested
        output.assertContains(asString(th));
    }

    @SuppressWarnings("ConstantConditions")
    @Test
    public void testStdErrMsgThrowableNull()
    {
        // Initialize Logger
        StdErrLog log = new StdErrLog("testX", new Properties());
        StdErrCapture output = new StdErrCapture(log);

        // Test behavior
        Throwable th = null;
        log.warn("ex", th);
        output.assertContains("testX");
        output.assertNotContains("null");
    }

    @Test
    public void testStdErrThrowable()
    {
        // The test Throwable
        Throwable th = new Throwable("Message");

        // Initialize Logger
        StdErrLog log = new StdErrLog("testX", new Properties());
        StdErrCapture output = new StdErrCapture(log);

        // Test behavior
        log.warn(th);
        output.assertContains(asString(th));
    }

    @Test
    public void testStdErrThrowableNull()
    {
        // Initialize Logger
        StdErrLog log = new StdErrLog("testX", new Properties());
        StdErrCapture output = new StdErrCapture(log);

        // Test behavior
        Throwable th = null;
        log.warn(th); // Behavior here is being tested
        output.assertContains("testX");
        output.assertNotContains("null");
    }

    @Test
    public void testStdErrFormatArgsThrowable()
    {
        // The test throwable
        Throwable th = new Throwable("Reasons Explained");

        // Initialize Logger
        StdErrLog log = new StdErrLog("testX", new Properties());
        StdErrCapture output = new StdErrCapture(log);

        // Test behavior
        log.warn("Ex {}", "Reasons", th);
        output.assertContains("Reasons");
        output.assertContains(asString(th));
    }

    @SuppressWarnings("ConstantConditions")
    @Test
    public void testStdErrFormatArgsThrowableNull()
    {
        // The test throwable
        Throwable th = null;

        // Initialize Logger
        StdErrLog log = new StdErrLog("testX", new Properties());
        StdErrCapture output = new StdErrCapture(log);

        // Test behavior
        log.warn("Ex {}", "Reasons", th);
        output.assertContains("Reasons");
        output.assertNotContains("null");
    }

    @Test
    public void testStdErrMsgThrowableWithControlChars()
    {
        // The test throwable, using "\b" (backspace) character
        Throwable th = new Throwable("Message with \b backspace");

        // Initialize Logger
        StdErrLog log = new StdErrLog("testX", new Properties());
        StdErrCapture output = new StdErrCapture(log);

        // Test behavior
        log.warn("ex", th);
        output.assertNotContains("Message with \b backspace");
        output.assertContains("Message with ? backspace");
    }

    @Test
    public void testStdErrMsgStringThrowableWithControlChars()
    {
        // The test throwable, using "\b" (backspace) character
        Throwable th = new Throwable("Message with \b backspace");

        // Initialize Logger
        StdErrLog log = new StdErrLog("testX", new Properties());
        StdErrCapture output = new StdErrCapture(log);

        // Test behavior
        log.info(th.toString());
        output.assertNotContains("Message with \b backspace");
        output.assertContains("Message with ? backspace");
    }

    private String asString(Throwable cause)
    {
        StringWriter tout = new StringWriter();
        cause.printStackTrace(new PrintWriter(tout));
        return tout.toString();
    }

    /**
     * Test to make sure that using a Null parameter on parameterized messages does not result in a NPE
     */
    @Test
    public void testParameterizedMessageNullValues()
    {
        StdErrLog log = new StdErrLog(StdErrLogTest.class.getName(), new Properties());
        log.setLevel(StdErrLog.LEVEL_DEBUG);
        try (StacklessLogging ignored = new StacklessLogging(log))
        {
            log.info("Testing info(msg,null,null) - {} {}", "arg0", "arg1");
            log.info("Testing info(msg,null,null) - {} {}", null, null);
            log.info("Testing info(msg,null,null) - {}", null, null);
            log.info("Testing info(msg,null,null)", null, null);
            log.info(null, "Testing", "info(null,arg0,arg1)");
            log.info(null, null, null);

            log.debug("Testing debug(msg,null,null) - {} {}", "arg0", "arg1");
            log.debug("Testing debug(msg,null,null) - {} {}", null, null);
            log.debug("Testing debug(msg,null,null) - {}", null, null);
            log.debug("Testing debug(msg,null,null)", null, null);
            log.debug(null, "Testing", "debug(null,arg0,arg1)");
            log.debug(null, null, null);

            log.debug("Testing debug(msg,null)");
            log.debug(null, new Throwable("Testing debug(null,thrw)").fillInStackTrace());

            log.warn("Testing warn(msg,null,null) - {} {}", "arg0", "arg1");
            log.warn("Testing warn(msg,null,null) - {} {}", null, null);
            log.warn("Testing warn(msg,null,null) - {}", null, null);
            log.warn("Testing warn(msg,null,null)", null, null);
            log.warn(null, "Testing", "warn(msg,arg0,arg1)");
            log.warn(null, null, null);

            log.warn("Testing warn(msg,null)");
            log.warn(null, new Throwable("Testing warn(msg,thrw)").fillInStackTrace());
        }
    }

    @Test
    public void testGetLoggingLevelDefault()
    {
        Properties props = new Properties();

        // Default Levels
        assertEquals(StdErrLog.LEVEL_INFO, StdErrLog.getLoggingLevel(props, null), "Default Logging Level");
        assertEquals(StdErrLog.LEVEL_INFO, StdErrLog.getLoggingLevel(props, ""), "Default Logging Level");
        assertEquals(StdErrLog.LEVEL_INFO, StdErrLog.getLoggingLevel(props, "org.eclipse.jetty"), "Default Logging Level");
        assertEquals(StdErrLog.LEVEL_INFO, StdErrLog.getLoggingLevel(props, StdErrLogTest.class.getName()), "Default Logging Level");
    }

    @Test
    public void testGetLoggingLevelBad()
    {
        Properties props = new Properties();
        props.setProperty("log.LEVEL", "WARN");
        props.setProperty("org.eclipse.jetty.bad.LEVEL", "EXPECTED_BAD_LEVEL");

        // Default Level (because of bad level value)
        assertEquals(StdErrLog.LEVEL_WARN, StdErrLog.getLoggingLevel(props, "org.eclipse.jetty.bad"), "Bad Logging Level");
    }

    @Test
    public void testGetLoggingLevelLowercase()
    {
        Properties props = new Properties();
        props.setProperty("log.LEVEL", "warn");
        props.setProperty("org.eclipse.jetty.util.LEVEL", "info");

        // Default Level
        assertEquals(StdErrLog.LEVEL_WARN, StdErrLog.getLoggingLevel(props, "org.eclipse.jetty"), "Lowercase Level");
        // Specific Level
        assertEquals(StdErrLog.LEVEL_INFO, StdErrLog.getLoggingLevel(props, "org.eclipse.jetty.util"), "Lowercase Level");
    }

    @Test
    public void testGetLoggingLevelRoot()
    {
        Properties props = new Properties();
        props.setProperty("log.LEVEL", "DEBUG");

        // Default Levels
        assertEquals(StdErrLog.LEVEL_DEBUG, StdErrLog.getLoggingLevel(props, null), "Default Logging Level");
        assertEquals(StdErrLog.LEVEL_DEBUG, StdErrLog.getLoggingLevel(props, ""), "Default Logging Level");
        assertEquals(StdErrLog.LEVEL_DEBUG, StdErrLog.getLoggingLevel(props, "org.eclipse.jetty"), "Default Logging Level");
        assertEquals(StdErrLog.LEVEL_DEBUG, StdErrLog.getLoggingLevel(props, StdErrLogTest.class.getName()), "Default Logging Level");
    }

    @Test
    public void testGetLoggingLevelFQCN()
    {
        String name = StdErrLogTest.class.getName();
        Properties props = new Properties();
        props.setProperty(name + ".LEVEL", "ALL");

        // Default Levels
        assertEquals(StdErrLog.LEVEL_INFO, StdErrLog.getLoggingLevel(props, null));
        assertEquals(StdErrLog.LEVEL_INFO, StdErrLog.getLoggingLevel(props, ""));
        assertEquals(StdErrLog.LEVEL_INFO, StdErrLog.getLoggingLevel(props, "org.eclipse.jetty"));

        // Specified Level
        assertEquals(StdErrLog.LEVEL_ALL, StdErrLog.getLoggingLevel(props, name));
    }

    @Test
    public void testGetLoggingLevelUtilLevel()
    {
        Properties props = new Properties();
        props.setProperty("org.eclipse.jetty.util.LEVEL", "DEBUG");

        // Default Levels
        assertEquals(StdErrLog.LEVEL_INFO, StdErrLog.getLoggingLevel(props, null));
        assertEquals(StdErrLog.LEVEL_INFO, StdErrLog.getLoggingLevel(props, ""));
        assertEquals(StdErrLog.LEVEL_INFO, StdErrLog.getLoggingLevel(props, "org.eclipse.jetty"));
        assertEquals(StdErrLog.LEVEL_INFO, StdErrLog.getLoggingLevel(props, "org.eclipse.jetty.server.BogusObject"));

        // Configured Level
        assertEquals(StdErrLog.LEVEL_DEBUG, StdErrLog.getLoggingLevel(props, StdErrLogTest.class.getName()));
        assertEquals(StdErrLog.LEVEL_DEBUG, StdErrLog.getLoggingLevel(props, "org.eclipse.jetty.util.Bogus"));
        assertEquals(StdErrLog.LEVEL_DEBUG, StdErrLog.getLoggingLevel(props, "org.eclipse.jetty.util"));
        assertEquals(StdErrLog.LEVEL_DEBUG, StdErrLog.getLoggingLevel(props, "org.eclipse.jetty.util.resource.FileResource"));
    }

    @Test
    public void testGetLoggingLevelMixedLevels()
    {
        Properties props = new Properties();
        props.setProperty("log.LEVEL", "DEBUG");
        props.setProperty("org.eclipse.jetty.util.LEVEL", "WARN");
        props.setProperty("org.eclipse.jetty.util.ConcurrentHashMap.LEVEL", "ALL");

        // Default Levels
        assertEquals(StdErrLog.LEVEL_DEBUG, StdErrLog.getLoggingLevel(props, null));
        assertEquals(StdErrLog.LEVEL_DEBUG, StdErrLog.getLoggingLevel(props, ""));
        assertEquals(StdErrLog.LEVEL_DEBUG, StdErrLog.getLoggingLevel(props, "org.eclipse.jetty"));
        assertEquals(StdErrLog.LEVEL_DEBUG, StdErrLog.getLoggingLevel(props, "org.eclipse.jetty.server.ServerObject"));

        // Configured Level
        assertEquals(StdErrLog.LEVEL_WARN, StdErrLog.getLoggingLevel(props, StdErrLogTest.class.getName()));
        assertEquals(StdErrLog.LEVEL_WARN, StdErrLog.getLoggingLevel(props, "org.eclipse.jetty.util.MagicUtil"));
        assertEquals(StdErrLog.LEVEL_WARN, StdErrLog.getLoggingLevel(props, "org.eclipse.jetty.util"));
        assertEquals(StdErrLog.LEVEL_WARN, StdErrLog.getLoggingLevel(props, "org.eclipse.jetty.util.resource.FileResource"));
        assertEquals(StdErrLog.LEVEL_ALL, StdErrLog.getLoggingLevel(props, "org.eclipse.jetty.util.ConcurrentHashMap"));
    }

    /**
     * Tests StdErrLog.warn() methods with level filtering.
     * <p>
     * Should always see WARN level messages, regardless of set level.
     */
    @Test
    public void testWarnFiltering()
    {
        StdErrLog log = new StdErrLog(StdErrLogTest.class.getName(), new Properties());
        try (StacklessLogging ignored = new StacklessLogging(log))
        {
            StdErrCapture output = new StdErrCapture(log);

            // Start with default level
            log.warn("See Me");

            // Set to debug level
            log.setLevel(StdErrLog.LEVEL_DEBUG);
            log.warn("Hear Me");

            // Set to warn level
            log.setLevel(StdErrLog.LEVEL_WARN);
            log.warn("Cheer Me");

            log.warn("<zoom>", new Throwable("out of focus"));
            log.warn(new Throwable("scene lost"));

            // Validate Output
            // System.err.print(output);
            output.assertContains("See Me");
            output.assertContains("Hear Me");
            output.assertContains("Cheer Me");

            // Validate Stack Traces
            output.assertContains(".StdErrLogTest:tname: <zoom>");
            output.assertContains("java.lang.Throwable: out of focus");
            output.assertContains("java.lang.Throwable: scene lost");
        }
    }

    /**
     * Tests StdErrLog.info() methods with level filtering.
     * <p>
     * Should only see INFO level messages when level is set to {@link StdErrLog#LEVEL_INFO} and below.
     */
    @Test
    public void testInfoFiltering()
    {
        StdErrLog log = new StdErrLog(StdErrLogTest.class.getName(), new Properties());
        try (StacklessLogging ignored = new StacklessLogging(log))
        {
            StdErrCapture output = new StdErrCapture(log);

            // Normal/Default behavior
            log.info("I will not buy");

            // Level Debug
            log.setLevel(StdErrLog.LEVEL_DEBUG);
            log.info("this record");

            // Level All
            log.setLevel(StdErrLog.LEVEL_ALL);
            log.info("it is scratched.");

            log.info("<zoom>", new Throwable("out of focus"));
            log.info(new Throwable("scene lost"));

            // Level Warn
            log.setLevel(StdErrLog.LEVEL_WARN);
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

            output.assertContains(".StdErrLogTest:tname: <zoom>");
            output.assertContains("java.lang.Throwable: out of focus");
            output.assertContains("java.lang.Throwable: scene lost");
        }
    }

    /**
     * Tests {@link StdErrLog#LEVEL_OFF} filtering.
     */
    @Test
    public void testOffFiltering()
    {
        StdErrLog log = new StdErrLog(StdErrLogTest.class.getName(), new Properties());
        try (StacklessLogging ignored = new StacklessLogging(log))
        {
            log.setLevel(StdErrLog.LEVEL_OFF);

            StdErrCapture output = new StdErrCapture(log);

            // Various logging events
            log.debug("Squelch");
            log.debug("Squelch", new RuntimeException("Squelch"));
            log.info("Squelch");
            log.info("Squelch", new IllegalStateException("Squelch"));
            log.warn("Squelch");
            log.warn("Squelch", new Exception("Squelch"));
            log.ignore(new Throwable("Squelch"));

            // Validate Output
            output.assertNotContains("Squelch");
        }
    }

    /**
     * Tests StdErrLog.debug() methods with level filtering.
     * <p>
     * Should only see DEBUG level messages when level is set to {@link StdErrLog#LEVEL_DEBUG} and below.
     */
    @Test
    public void testDebugFiltering()
    {
        StdErrLog log = new StdErrLog(StdErrLogTest.class.getName(), new Properties());
        try (StacklessLogging ignored = new StacklessLogging(log))
        {
            StdErrCapture output = new StdErrCapture(log);

            // Normal/Default behavior
            log.debug("Tobacconist");
            log.debug("<spoken line>", new Throwable("on editing room floor"));

            // Level Debug
            log.setLevel(StdErrLog.LEVEL_DEBUG);
            log.debug("my hovercraft is");

            log.debug("<zoom>", new Throwable("out of focus"));
            log.debug(new Throwable("scene lost"));

            // Level All
            log.setLevel(StdErrLog.LEVEL_ALL);
            log.debug("full of eels.");

            // Level Warn
            log.setLevel(StdErrLog.LEVEL_WARN);
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

            output.assertContains(".StdErrLogTest:tname: <zoom>");
            output.assertContains("java.lang.Throwable: out of focus");
            output.assertContains("java.lang.Throwable: scene lost");
        }
    }

    /**
     * Tests StdErrLog with {@link Logger#ignore(Throwable)} use.
     * <p>
     * Should only see IGNORED level messages when level is set to {@link StdErrLog#LEVEL_ALL}.
     */
    @Test
    public void testIgnores()
    {
        StdErrLog log = new StdErrLog(StdErrLogTest.class.getName(), new Properties());
        try (StacklessLogging ignored = new StacklessLogging(log))
        {
            StdErrCapture output = new StdErrCapture(log);

            // Normal/Default behavior
            log.ignore(new Throwable("IGNORE ME"));

            // Show Ignored
            log.setLevel(StdErrLog.LEVEL_ALL);
            log.ignore(new Throwable("Don't ignore me"));

            // Set to Debug level
            log.setLevel(StdErrLog.LEVEL_DEBUG);
            log.ignore(new Throwable("Debug me"));

            // Validate Output
            // System.err.print(output);
            output.assertNotContains("IGNORE ME");
            output.assertContains("Don't ignore me");
            output.assertNotContains("Debug me");
        }
    }

    @Test
    public void testIsDebugEnabled()
    {
        StdErrLog log = new StdErrLog(StdErrLogTest.class.getName(), new Properties());
        try (StacklessLogging ignored = new StacklessLogging(log))
        {
            log.setLevel(StdErrLog.LEVEL_ALL);
            assertThat("log.level(all).isDebugEnabled", log.isDebugEnabled(), is(true));

            log.setLevel(StdErrLog.LEVEL_DEBUG);
            assertThat("log.level(debug).isDebugEnabled", log.isDebugEnabled(), is(true));

            log.setLevel(StdErrLog.LEVEL_INFO);
            assertThat("log.level(info).isDebugEnabled", log.isDebugEnabled(), is(false));

            log.setLevel(StdErrLog.LEVEL_WARN);
            assertThat("log.level(warn).isDebugEnabled", log.isDebugEnabled(), is(false));

            log.setLevel(StdErrLog.LEVEL_OFF);
            assertThat("log.level(off).isDebugEnabled", log.isDebugEnabled(), is(false));
        }
    }

    @Test
    public void testSetGetLevel()
    {
        StdErrLog log = new StdErrLog(StdErrLogTest.class.getName(), new Properties());
        try (StacklessLogging ignored = new StacklessLogging(log))
        {
            log.setLevel(StdErrLog.LEVEL_ALL);
            assertThat("log.level(all).getLevel()", log.getLevel(), is(StdErrLog.LEVEL_ALL));

            log.setLevel(StdErrLog.LEVEL_DEBUG);
            assertThat("log.level(debug).getLevel()", log.getLevel(), is(StdErrLog.LEVEL_DEBUG));

            log.setLevel(StdErrLog.LEVEL_INFO);
            assertThat("log.level(info).getLevel()", log.getLevel(), is(StdErrLog.LEVEL_INFO));

            log.setLevel(StdErrLog.LEVEL_WARN);
            assertThat("log.level(warn).getLevel()", log.getLevel(), is(StdErrLog.LEVEL_WARN));

            log.setLevel(StdErrLog.LEVEL_OFF);
            assertThat("log.level(off).getLevel()", log.getLevel(), is(StdErrLog.LEVEL_OFF));
        }
    }

    @Test
    public void testGetChildLoggerSimple()
    {
        String baseName = "jetty";
        StdErrLog log = new StdErrLog(baseName, new Properties());
        try (StacklessLogging ignored = new StacklessLogging(log))
        {
            assertThat("Logger.name", log.getName(), is("jetty"));

            Logger log2 = log.getLogger("child");
            assertThat("Logger.child.name", log2.getName(), is("jetty.child"));
        }
    }

    @Test
    public void testGetChildLoggerDeep()
    {
        String baseName = "jetty";
        StdErrLog log = new StdErrLog(baseName, new Properties());
        try (StacklessLogging ignored = new StacklessLogging(log))
        {
            assertThat("Logger.name", log.getName(), is("jetty"));

            Logger log2 = log.getLogger("child.of.the.sixties");
            assertThat("Logger.child.name", log2.getName(), is("jetty.child.of.the.sixties"));
        }
    }

    @Test
    public void testGetChildLoggerNull()
    {
        String baseName = "jetty";
        StdErrLog log = new StdErrLog(baseName, new Properties());
        try (StacklessLogging ignored = new StacklessLogging(log))
        {
            assertThat("Logger.name", log.getName(), is("jetty"));

            // Pass null as child reference, should return parent logger
            Logger log2 = log.getLogger((String)null);
            assertThat("Logger.child.name", log2.getName(), is("jetty"));
            assertSame(log2, log, "Should have returned same logger");
        }
    }

    @Test
    public void testGetChildLoggerEmptyName()
    {
        String baseName = "jetty";
        StdErrLog log = new StdErrLog(baseName, new Properties());
        try (StacklessLogging ignored = new StacklessLogging(log))
        {
            assertThat("Logger.name", log.getName(), is("jetty"));

            // Pass empty name as child reference, should return parent logger
            Logger log2 = log.getLogger("");
            assertThat("Logger.child.name", log2.getName(), is("jetty"));
            assertSame(log2, log, "Should have returned same logger");
        }
    }

    @Test
    public void testGetChildLoggerEmptyNameSpaces()
    {
        String baseName = "jetty";
        StdErrLog log = new StdErrLog(baseName, new Properties());
        try (StacklessLogging ignored = new StacklessLogging(log))
        {
            assertThat("Logger.name", log.getName(), is("jetty"));

            // Pass empty name as child reference, should return parent logger
            Logger log2 = log.getLogger("      ");
            assertThat("Logger.child.name", log2.getName(), is("jetty"));
            assertSame(log2, log, "Should have returned same logger");
        }
    }

    @Test
    public void testGetChildLoggerNullParent()
    {
        AbstractLogger log = new StdErrLog(null, new Properties());

        assertThat("Logger.name", log.getName(), is(""));

        Logger log2 = log.getLogger("jetty");
        assertThat("Logger.child.name", log2.getName(), is("jetty"));
        assertNotSame(log2, log, "Should have returned same logger");
    }

    @Test
    public void testToString()
    {
        StdErrLog log = new StdErrLog("jetty", new Properties());

        log.setLevel(StdErrLog.LEVEL_ALL);
        assertThat("Logger.toString", log.toString(), is("StdErrLog:jetty:LEVEL=ALL"));

        log.setLevel(StdErrLog.LEVEL_DEBUG);
        assertThat("Logger.toString", log.toString(), is("StdErrLog:jetty:LEVEL=DEBUG"));

        log.setLevel(StdErrLog.LEVEL_INFO);
        assertThat("Logger.toString", log.toString(), is("StdErrLog:jetty:LEVEL=INFO"));

        log.setLevel(StdErrLog.LEVEL_WARN);
        assertThat("Logger.toString", log.toString(), is("StdErrLog:jetty:LEVEL=WARN"));

        log.setLevel(99); // intentionally bogus level
        assertThat("Logger.toString", log.toString(), is("StdErrLog:jetty:LEVEL=?"));
    }

    @Test
    public void testPrintSource()
    {
        Properties props = new Properties();
        props.put("test.SOURCE", "true");
        StdErrLog log = new StdErrLog("test", props);
        log.setLevel(StdErrLog.LEVEL_DEBUG);

        ByteArrayOutputStream test = new ByteArrayOutputStream();
        PrintStream err = new PrintStream(test);
        log.setStdErrStream(err);

        log.debug("Show me the source!");

        String output = new String(test.toByteArray(), StandardCharsets.UTF_8);
        // System.err.print(output);

        assertThat(output, containsString(".StdErrLogTest#testPrintSource(StdErrLogTest.java:"));

        props.put("test.SOURCE", "false");
    }

    @Test
    public void testConfiguredAndSetDebugEnabled()
    {
        Properties props = new Properties();
        props.setProperty("org.eclipse.jetty.util.LEVEL", "WARN");
        props.setProperty("org.eclipse.jetty.io.LEVEL", "WARN");

        StdErrLog root = new StdErrLog("", props);
        assertLevel(root, StdErrLog.LEVEL_INFO); // default

        StdErrLog log = (StdErrLog)root.getLogger(StdErrLogTest.class.getName());
        assertThat("Log.isDebugEnabled()", log.isDebugEnabled(), is(false));
        assertLevel(log, StdErrLog.LEVEL_WARN); // as configured

        // Boot stomp it all to debug
        root.setDebugEnabled(true);
        assertThat("Log.isDebugEnabled()", log.isDebugEnabled(), is(true));
        assertLevel(log, StdErrLog.LEVEL_DEBUG); // as stomped

        // Restore configured
        root.setDebugEnabled(false);
        assertThat("Log.isDebugEnabled()", log.isDebugEnabled(), is(false));
        assertLevel(log, StdErrLog.LEVEL_WARN); // as configured
    }

    @Test
    public void testSuppressed()
    {
        StdErrLog log = new StdErrLog("xxx", new Properties());
        StdErrCapture output = new StdErrCapture(log);

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

    private void assertLevel(StdErrLog log, int expectedLevel)
    {
        assertThat("Log[" + log.getName() + "].level", levelToString(log.getLevel()), is(levelToString(expectedLevel)));
    }

    private String levelToString(int level)
    {
        switch (level)
        {
            case StdErrLog.LEVEL_ALL:
                return "ALL";
            case StdErrLog.LEVEL_DEBUG:
                return "DEBUG";
            case StdErrLog.LEVEL_INFO:
                return "INFO";
            case StdErrLog.LEVEL_WARN:
                return "WARN";
            default:
                return Integer.toString(level);
        }
    }
}
