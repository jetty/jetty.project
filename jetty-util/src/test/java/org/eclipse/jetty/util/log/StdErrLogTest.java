//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for StdErrLog
 */
public class StdErrLogTest
{
    static
    {
        StdErrLog.setTagPad(0);
    }
    
    @Before
    public void before()
    {
        Thread.currentThread().setName("tname");
    }

    @Test
    public void testStdErrLogFormat() throws UnsupportedEncodingException
    {
        StdErrLog log = new StdErrLog(LogTest.class.getName(),new Properties());
        StdErrCapture output = new StdErrCapture(log);

        log.info("testing:{},{}","test","format1");
        log.info("testing:{}","test","format2");
        log.info("testing","test","format3");
        log.info("testing:{},{}","test",null);
        log.info("testing {} {}",null,null);
        log.info("testing:{}",null,null);
        log.info("testing",null,null);

        System.err.println(output);
        output.assertContains("INFO:oejul.LogTest:tname: testing:test,format1");
        output.assertContains("INFO:oejul.LogTest:tname: testing:test,format1");
        output.assertContains("INFO:oejul.LogTest:tname: testing:test format2");
        output.assertContains("INFO:oejul.LogTest:tname: testing test format3");
        output.assertContains("INFO:oejul.LogTest:tname: testing:test,null");
        output.assertContains("INFO:oejul.LogTest:tname: testing null null");
        output.assertContains("INFO:oejul.LogTest:tname: testing:null");
        output.assertContains("INFO:oejul.LogTest:tname: testing");
    }

    @Test
    public void testStdErrLogDebug()
    {
        StdErrLog log = new StdErrLog("xxx",new Properties());
        StdErrCapture output = new StdErrCapture(log);

        log.setLevel(StdErrLog.LEVEL_DEBUG);
        log.debug("testing {} {}","test","debug");
        log.info("testing {} {}","test","info");
        log.warn("testing {} {}","test","warn");
        log.setLevel(StdErrLog.LEVEL_INFO);
        log.debug("YOU SHOULD NOT SEE THIS!",null,null);

        // Test for backward compat with old (now deprecated) method
        Logger before = log.getLogger("before");
        log.setDebugEnabled(true);
        Logger after = log.getLogger("after");
        before.debug("testing {} {}","test","debug-before");
        log.debug("testing {} {}","test","debug-deprecated");
        after.debug("testing {} {}","test","debug-after");

        log.setDebugEnabled(false);
        before.debug("testing {} {}","test","debug-before-false");
        log.debug("testing {} {}","test","debug-deprecated-false");
        after.debug("testing {} {}","test","debug-after-false");

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
        StdErrLog log = new StdErrLog("test",new Properties());
        log.setPrintLongNames(true);
        StdErrCapture output = new StdErrCapture(log);

        Assert.assertThat("Log.name", log.getName(), is("test"));
        Logger next=log.getLogger("next");
        Assert.assertThat("Log.name(child)", next.getName(), is("test.next"));
        next.info("testing {} {}","next","info");

        output.assertContains(":test.next:tname: testing next info");
    }

    @Test
    public void testStdErrThrowable()
    {
        // Common Throwable (for test)
        Throwable th = new Throwable("Message");

        // Capture raw string form
        StringWriter tout = new StringWriter();
        th.printStackTrace(new PrintWriter(tout));
        String ths = tout.toString();

        // Start test
        StdErrLog log = new StdErrLog("test",new Properties());
        StdErrCapture output = new StdErrCapture(log);

        log.warn("ex",th);
        output.assertContains(ths);

        th = new Throwable("Message with \033 escape");

        log.warn("ex",th);
        output.assertNotContains("Message with \033 escape");
        log.info(th.toString());
        output.assertNotContains("Message with \033 escape");

        log.warn("ex",th);
        output.assertContains("Message with ? escape");
        log.info(th.toString());
        output.assertContains("Message with ? escape");
    }

    /**
     * Test to make sure that using a Null parameter on parameterized messages does not result in a NPE
     * @throws Exception failed test
     */
    @Test
    public void testParameterizedMessage_NullValues() throws Exception
    {
        StdErrLog log = new StdErrLog(StdErrLogTest.class.getName(),new Properties());
        log.setLevel(StdErrLog.LEVEL_DEBUG);
        try (StacklessLogging stackless = new StacklessLogging(log))
        {
            log.info("Testing info(msg,null,null) - {} {}","arg0","arg1");
            log.info("Testing info(msg,null,null) - {} {}",null,null);
            log.info("Testing info(msg,null,null) - {}",null,null);
            log.info("Testing info(msg,null,null)",null,null);
            log.info(null,"Testing","info(null,arg0,arg1)");
            log.info(null,null,null);

            log.debug("Testing debug(msg,null,null) - {} {}","arg0","arg1");
            log.debug("Testing debug(msg,null,null) - {} {}",null,null);
            log.debug("Testing debug(msg,null,null) - {}",null,null);
            log.debug("Testing debug(msg,null,null)",null,null);
            log.debug(null,"Testing","debug(null,arg0,arg1)");
            log.debug(null,null,null);

            log.debug("Testing debug(msg,null)");
            log.debug(null,new Throwable("Testing debug(null,thrw)").fillInStackTrace());

            log.warn("Testing warn(msg,null,null) - {} {}","arg0","arg1");
            log.warn("Testing warn(msg,null,null) - {} {}",null,null);
            log.warn("Testing warn(msg,null,null) - {}",null,null);
            log.warn("Testing warn(msg,null,null)",null,null);
            log.warn(null,"Testing","warn(msg,arg0,arg1)");
            log.warn(null,null,null);

            log.warn("Testing warn(msg,null)");
            log.warn(null,new Throwable("Testing warn(msg,thrw)").fillInStackTrace());
        }
    }

    @Test
    public void testGetLoggingLevel_Default()
    {
        Properties props = new Properties();

        // Default Levels
        Assert.assertEquals("Default Logging Level",StdErrLog.LEVEL_INFO,StdErrLog.getLoggingLevel(props,null));
        Assert.assertEquals("Default Logging Level",StdErrLog.LEVEL_INFO,StdErrLog.getLoggingLevel(props,""));
        Assert.assertEquals("Default Logging Level",StdErrLog.LEVEL_INFO,StdErrLog.getLoggingLevel(props,"org.eclipse.jetty"));
        Assert.assertEquals("Default Logging Level",StdErrLog.LEVEL_INFO,StdErrLog.getLoggingLevel(props,StdErrLogTest.class.getName()));
    }

    @Test
    public void testGetLoggingLevel_Bad()
    {
        Properties props = new Properties();
        props.setProperty("log.LEVEL", "WARN");
        props.setProperty("org.eclipse.jetty.bad.LEVEL","EXPECTED_BAD_LEVEL");

        // Default Level (because of bad level value)
        Assert.assertEquals("Bad Logging Level",StdErrLog.LEVEL_WARN,StdErrLog.getLoggingLevel(props,"org.eclipse.jetty.bad"));
    }

    @Test
    public void testGetLoggingLevel_Lowercase()
    {
        Properties props = new Properties();
        props.setProperty("log.LEVEL", "warn");
        props.setProperty("org.eclipse.jetty.util.LEVEL","info");

        // Default Level
        Assert.assertEquals("Lowercase Level",StdErrLog.LEVEL_WARN,StdErrLog.getLoggingLevel(props,"org.eclipse.jetty"));
        // Specific Level
        Assert.assertEquals("Lowercase Level",StdErrLog.LEVEL_INFO,StdErrLog.getLoggingLevel(props,"org.eclipse.jetty.util"));
    }

    @Test
    public void testGetLoggingLevel_Root()
    {
        Properties props = new Properties();
        props.setProperty("log.LEVEL","DEBUG");

        // Default Levels
        Assert.assertEquals("Default Logging Level",StdErrLog.LEVEL_DEBUG,StdErrLog.getLoggingLevel(props,null));
        Assert.assertEquals("Default Logging Level",StdErrLog.LEVEL_DEBUG,StdErrLog.getLoggingLevel(props,""));
        Assert.assertEquals("Default Logging Level",StdErrLog.LEVEL_DEBUG,StdErrLog.getLoggingLevel(props,"org.eclipse.jetty"));
        Assert.assertEquals("Default Logging Level",StdErrLog.LEVEL_DEBUG,StdErrLog.getLoggingLevel(props,StdErrLogTest.class.getName()));
    }

    @Test
    public void testGetLoggingLevel_FQCN()
    {
        String name = StdErrLogTest.class.getName();
        Properties props = new Properties();
        props.setProperty(name + ".LEVEL","ALL");

        // Default Levels
        Assert.assertEquals(StdErrLog.LEVEL_INFO,StdErrLog.getLoggingLevel(props,null));
        Assert.assertEquals(StdErrLog.LEVEL_INFO,StdErrLog.getLoggingLevel(props,""));
        Assert.assertEquals(StdErrLog.LEVEL_INFO,StdErrLog.getLoggingLevel(props,"org.eclipse.jetty"));

        // Specified Level
        Assert.assertEquals(StdErrLog.LEVEL_ALL,StdErrLog.getLoggingLevel(props,name));
    }

    @Test
    public void testGetLoggingLevel_UtilLevel()
    {
        Properties props = new Properties();
        props.setProperty("org.eclipse.jetty.util.LEVEL","DEBUG");

        // Default Levels
        Assert.assertEquals(StdErrLog.LEVEL_INFO,StdErrLog.getLoggingLevel(props,null));
        Assert.assertEquals(StdErrLog.LEVEL_INFO,StdErrLog.getLoggingLevel(props,""));
        Assert.assertEquals(StdErrLog.LEVEL_INFO,StdErrLog.getLoggingLevel(props,"org.eclipse.jetty"));
        Assert.assertEquals(StdErrLog.LEVEL_INFO,StdErrLog.getLoggingLevel(props,"org.eclipse.jetty.server.BogusObject"));

        // Configured Level
        Assert.assertEquals(StdErrLog.LEVEL_DEBUG,StdErrLog.getLoggingLevel(props,StdErrLogTest.class.getName()));
        Assert.assertEquals(StdErrLog.LEVEL_DEBUG,StdErrLog.getLoggingLevel(props,"org.eclipse.jetty.util.Bogus"));
        Assert.assertEquals(StdErrLog.LEVEL_DEBUG,StdErrLog.getLoggingLevel(props,"org.eclipse.jetty.util"));
        Assert.assertEquals(StdErrLog.LEVEL_DEBUG,StdErrLog.getLoggingLevel(props,"org.eclipse.jetty.util.resource.FileResource"));
    }

    @Test
    public void testGetLoggingLevel_MixedLevels()
    {
        Properties props = new Properties();
        props.setProperty("log.LEVEL","DEBUG");
        props.setProperty("org.eclipse.jetty.util.LEVEL","WARN");
        props.setProperty("org.eclipse.jetty.util.ConcurrentHashMap.LEVEL","ALL");

        // Default Levels
        Assert.assertEquals(StdErrLog.LEVEL_DEBUG,StdErrLog.getLoggingLevel(props,null));
        Assert.assertEquals(StdErrLog.LEVEL_DEBUG,StdErrLog.getLoggingLevel(props,""));
        Assert.assertEquals(StdErrLog.LEVEL_DEBUG,StdErrLog.getLoggingLevel(props,"org.eclipse.jetty"));
        Assert.assertEquals(StdErrLog.LEVEL_DEBUG,StdErrLog.getLoggingLevel(props,"org.eclipse.jetty.server.ServerObject"));

        // Configured Level
        Assert.assertEquals(StdErrLog.LEVEL_WARN,StdErrLog.getLoggingLevel(props,StdErrLogTest.class.getName()));
        Assert.assertEquals(StdErrLog.LEVEL_WARN,StdErrLog.getLoggingLevel(props,"org.eclipse.jetty.util.MagicUtil"));
        Assert.assertEquals(StdErrLog.LEVEL_WARN,StdErrLog.getLoggingLevel(props,"org.eclipse.jetty.util"));
        Assert.assertEquals(StdErrLog.LEVEL_WARN,StdErrLog.getLoggingLevel(props,"org.eclipse.jetty.util.resource.FileResource"));
        Assert.assertEquals(StdErrLog.LEVEL_ALL,StdErrLog.getLoggingLevel(props,"org.eclipse.jetty.util.ConcurrentHashMap"));
    }

    /**
     * Tests StdErrLog.warn() methods with level filtering.
     * <p>
     * Should always see WARN level messages, regardless of set level.
     * @throws UnsupportedEncodingException failed test
     */
    @Test
    public void testWarnFiltering() throws UnsupportedEncodingException
    {
        StdErrLog log = new StdErrLog(StdErrLogTest.class.getName(),new Properties());
        try (StacklessLogging stackless = new StacklessLogging(log))
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
     * @throws Exception failed test
     */
    @Test
    public void testInfoFiltering() throws Exception
    {
        StdErrLog log = new StdErrLog(StdErrLogTest.class.getName(),new Properties());
        try (StacklessLogging stackless = new StacklessLogging(log))
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
     * @throws Exception failed test
     */
    @Test
    public void testOffFiltering() throws Exception
    {
        StdErrLog log = new StdErrLog(StdErrLogTest.class.getName(),new Properties());
        try (StacklessLogging stackless = new StacklessLogging(log))
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
     * @throws Exception failed test
     */
    @Test
    public void testDebugFiltering() throws Exception
    {
        StdErrLog log = new StdErrLog(StdErrLogTest.class.getName(),new Properties());
        try(StacklessLogging stackless = new StacklessLogging(log))
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
     * @throws Exception failed test
     */
    @Test
    public void testIgnores() throws Exception
    {
        StdErrLog log = new StdErrLog(StdErrLogTest.class.getName(),new Properties());
        try (StacklessLogging stackless = new StacklessLogging(log))
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
    public void testIsDebugEnabled() throws Exception
    {
        StdErrLog log = new StdErrLog(StdErrLogTest.class.getName(),new Properties());
        try (StacklessLogging stackless = new StacklessLogging(log))
        {
            log.setLevel(StdErrLog.LEVEL_ALL);
            Assert.assertThat("log.level(all).isDebugEnabled", log.isDebugEnabled(), is(true));

            log.setLevel(StdErrLog.LEVEL_DEBUG);
            Assert.assertThat("log.level(debug).isDebugEnabled", log.isDebugEnabled(), is(true));

            log.setLevel(StdErrLog.LEVEL_INFO);
            Assert.assertThat("log.level(info).isDebugEnabled", log.isDebugEnabled(), is(false));

            log.setLevel(StdErrLog.LEVEL_WARN);
            Assert.assertThat("log.level(warn).isDebugEnabled", log.isDebugEnabled(), is(false));

            log.setLevel(StdErrLog.LEVEL_OFF);
            Assert.assertThat("log.level(off).isDebugEnabled", log.isDebugEnabled(), is(false));
        }
    }

    @Test
    public void testSetGetLevel()
    {
        StdErrLog log = new StdErrLog(StdErrLogTest.class.getName(),new Properties());
        try (StacklessLogging stackless = new StacklessLogging(log))
        {
            log.setLevel(StdErrLog.LEVEL_ALL);
            Assert.assertThat("log.level(all).getLevel()", log.getLevel(), is(StdErrLog.LEVEL_ALL));

            log.setLevel(StdErrLog.LEVEL_DEBUG);
            Assert.assertThat("log.level(debug).getLevel()", log.getLevel(), is(StdErrLog.LEVEL_DEBUG));

            log.setLevel(StdErrLog.LEVEL_INFO);
            Assert.assertThat("log.level(info).getLevel()", log.getLevel(), is(StdErrLog.LEVEL_INFO));

            log.setLevel(StdErrLog.LEVEL_WARN);
            Assert.assertThat("log.level(warn).getLevel()", log.getLevel(), is(StdErrLog.LEVEL_WARN));

            log.setLevel(StdErrLog.LEVEL_OFF);
            Assert.assertThat("log.level(off).getLevel()", log.getLevel(), is(StdErrLog.LEVEL_OFF));
        }
    }

    @Test
    public void testGetChildLogger_Simple()
    {
        String baseName = "jetty";
        StdErrLog log = new StdErrLog(baseName,new Properties());
        try (StacklessLogging stackless = new StacklessLogging(log))
        {
            Assert.assertThat("Logger.name", log.getName(), is("jetty"));

            Logger log2 = log.getLogger("child");
            Assert.assertThat("Logger.child.name", log2.getName(), is("jetty.child"));
        }
    }

    @Test
    public void testGetChildLogger_Deep()
    {
        String baseName = "jetty";
        StdErrLog log = new StdErrLog(baseName,new Properties());
        try (StacklessLogging stackless = new StacklessLogging(log))
        {
            Assert.assertThat("Logger.name", log.getName(), is("jetty"));

            Logger log2 = log.getLogger("child.of.the.sixties");
            Assert.assertThat("Logger.child.name", log2.getName(), is("jetty.child.of.the.sixties"));
        }
    }

    @Test
    public void testGetChildLogger_Null()
    {
        String baseName = "jetty";
        StdErrLog log = new StdErrLog(baseName,new Properties());
        try (StacklessLogging stackless = new StacklessLogging(log))
        {
            Assert.assertThat("Logger.name", log.getName(), is("jetty"));

            // Pass null as child reference, should return parent logger
            Logger log2 = log.getLogger((String)null);
            Assert.assertThat("Logger.child.name", log2.getName(), is("jetty"));
            Assert.assertSame("Should have returned same logger", log2, log);
        }
    }

    @Test
    public void testGetChildLogger_EmptyName()
    {
        String baseName = "jetty";
        StdErrLog log = new StdErrLog(baseName,new Properties());
        try (StacklessLogging stackless = new StacklessLogging(log))
        {
            Assert.assertThat("Logger.name", log.getName(), is("jetty"));

            // Pass empty name as child reference, should return parent logger
            Logger log2 = log.getLogger("");
            Assert.assertThat("Logger.child.name", log2.getName(), is("jetty"));
            Assert.assertSame("Should have returned same logger", log2, log);
        }
    }

    @Test
    public void testGetChildLogger_EmptyNameSpaces()
    {
        String baseName = "jetty";
        StdErrLog log = new StdErrLog(baseName,new Properties());
        try (StacklessLogging stackless = new StacklessLogging(log))
        {
            Assert.assertThat("Logger.name", log.getName(), is("jetty"));

            // Pass empty name as child reference, should return parent logger
            Logger log2 = log.getLogger("      ");
            Assert.assertThat("Logger.child.name", log2.getName(), is("jetty"));
            Assert.assertSame("Should have returned same logger", log2, log);
        }
    }

    @Test
    public void testGetChildLogger_NullParent()
    {
        AbstractLogger log = new StdErrLog(null,new Properties());

        Assert.assertThat("Logger.name", log.getName(), is(""));

        Logger log2 = log.getLogger("jetty");
        Assert.assertThat("Logger.child.name", log2.getName(), is("jetty"));
        Assert.assertNotSame("Should have returned same logger", log2, log);
    }

    @Test
    public void testToString()
    {
        StdErrLog log = new StdErrLog("jetty",new Properties());

        log.setLevel(StdErrLog.LEVEL_ALL);
        Assert.assertThat("Logger.toString", log.toString(), is("StdErrLog:jetty:LEVEL=ALL"));

        log.setLevel(StdErrLog.LEVEL_DEBUG);
        Assert.assertThat("Logger.toString", log.toString(), is("StdErrLog:jetty:LEVEL=DEBUG"));

        log.setLevel(StdErrLog.LEVEL_INFO);
        Assert.assertThat("Logger.toString", log.toString(), is("StdErrLog:jetty:LEVEL=INFO"));

        log.setLevel(StdErrLog.LEVEL_WARN);
        Assert.assertThat("Logger.toString", log.toString(), is("StdErrLog:jetty:LEVEL=WARN"));

        log.setLevel(99); // intentionally bogus level
        Assert.assertThat("Logger.toString", log.toString(), is("StdErrLog:jetty:LEVEL=?"));
    }

    @Test
    public void testPrintSource() throws UnsupportedEncodingException
    {
        Properties props=new Properties();
        props.put("test.SOURCE","true");
        StdErrLog log = new StdErrLog("test",props);
        log.setLevel(StdErrLog.LEVEL_DEBUG);

        ByteArrayOutputStream test = new ByteArrayOutputStream();
        PrintStream err = new PrintStream(test);
        log.setStdErrStream(err);

        log.debug("Show me the source!");

        String output = new String(test.toByteArray(), StandardCharsets.UTF_8);
        // System.err.print(output);

        Assert.assertThat(output, containsString(".StdErrLogTest#testPrintSource(StdErrLogTest.java:"));
        

        props.put("test.SOURCE","false");
        log=new StdErrLog("other",props);
    }

    @Test
    public void testConfiguredAndSetDebugEnabled()
    {
        Properties props = new Properties();
        props.setProperty("org.eclipse.jetty.util.LEVEL","WARN");
        props.setProperty("org.eclipse.jetty.io.LEVEL", "WARN");

        StdErrLog root = new StdErrLog("", props);
        assertLevel(root,StdErrLog.LEVEL_INFO); // default

        StdErrLog log = (StdErrLog)root.getLogger(StdErrLogTest.class.getName());
        Assert.assertThat("Log.isDebugEnabled()", log.isDebugEnabled(), is(false));
        assertLevel(log,StdErrLog.LEVEL_WARN); // as configured

        // Boot stomp it all to debug
        root.setDebugEnabled(true);
        Assert.assertThat("Log.isDebugEnabled()", log.isDebugEnabled(), is(true));
        assertLevel(log,StdErrLog.LEVEL_DEBUG); // as stomped

        // Restore configured
        root.setDebugEnabled(false);
        Assert.assertThat("Log.isDebugEnabled()", log.isDebugEnabled(), is(false));
        assertLevel(log,StdErrLog.LEVEL_WARN); // as configured
    }

    @Test
    public void testSuppressed()
    {
        StdErrLog log = new StdErrLog("xxx",new Properties());
        StdErrCapture output = new StdErrCapture(log);

        Exception inner = new Exception("inner");
        inner.addSuppressed( new IllegalStateException(){{addSuppressed(new Exception("branch0"));}});
        IOException outer = new IOException("outer",inner);
        
        outer.addSuppressed( new IllegalStateException(){{addSuppressed(new Exception("branch1"));}});
        outer.addSuppressed( new IllegalArgumentException(){{addSuppressed(new Exception("branch2"));}});
        
        log.warn("problem",outer);

        output.assertContains("\t|\t|java.lang.Exception: branch2");
        output.assertContains("\t|\t|java.lang.Exception: branch1");
        output.assertContains("\t|\t|java.lang.Exception: branch0");
    }
    
    private void assertLevel(StdErrLog log, int expectedLevel)
    {
        Assert.assertThat("Log[" + log.getName() + "].level",levelToString(log.getLevel()),is(levelToString(expectedLevel)));
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
