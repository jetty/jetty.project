// ========================================================================
// Copyright (c) 2010-2010 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses.
// ========================================================================

package org.eclipse.jetty.util.log;

import static org.hamcrest.Matchers.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.util.Properties;

import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for StdErrLog
 */
public class StdErrLogTest
{
    /**
     * Test to make sure that using a Null parameter on parameterized messages does not result in a NPE
     */
    @Test
    public void testParameterizedMessage_NullValues() throws NullPointerException
    {
        StdErrLog log = new StdErrLog(StdErrLogTest.class.getName());
        log.setLevel(StdErrLog.LEVEL_DEBUG);
        log.setHideStacks(true);

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
        props.setProperty("org.eclipse.jetty.util.LEVEL","DEBUG");
        props.setProperty("org.eclipse.jetty.util.ConcurrentHashMap.LEVEL","ALL");

        // Default Levels
        Assert.assertEquals(StdErrLog.LEVEL_INFO,StdErrLog.getLoggingLevel(props,null));
        Assert.assertEquals(StdErrLog.LEVEL_INFO,StdErrLog.getLoggingLevel(props,""));
        Assert.assertEquals(StdErrLog.LEVEL_INFO,StdErrLog.getLoggingLevel(props,"org.eclipse.jetty"));
        Assert.assertEquals(StdErrLog.LEVEL_INFO,StdErrLog.getLoggingLevel(props,"org.eclipse.jetty.server.ServerObject"));
        
        // Configured Level
        Assert.assertEquals(StdErrLog.LEVEL_DEBUG,StdErrLog.getLoggingLevel(props,StdErrLogTest.class.getName()));
        Assert.assertEquals(StdErrLog.LEVEL_DEBUG,StdErrLog.getLoggingLevel(props,"org.eclipse.jetty.util.MagicUtil"));
        Assert.assertEquals(StdErrLog.LEVEL_DEBUG,StdErrLog.getLoggingLevel(props,"org.eclipse.jetty.util"));
        Assert.assertEquals(StdErrLog.LEVEL_DEBUG,StdErrLog.getLoggingLevel(props,"org.eclipse.jetty.util.resource.FileResource"));
        Assert.assertEquals(StdErrLog.LEVEL_ALL,StdErrLog.getLoggingLevel(props,"org.eclipse.jetty.util.ConcurrentHashMap"));
    }

    /**
     * Tests StdErrLog.warn() methods with level filtering.
     * <p>
     * Should always see WARN level messages, regardless of set level.
     */
    @Test
    public void testWarnFiltering() throws UnsupportedEncodingException
    {
        StdErrLog log = new StdErrLog(StdErrLogTest.class.getName());
        log.setHideStacks(true);

        ByteArrayOutputStream test = new ByteArrayOutputStream();
        PrintStream err = new PrintStream(test);
        log.setStdErrStream(err);

        // Start with default level
        log.warn("See Me");

        // Set to debug level
        log.setLevel(StdErrLog.LEVEL_DEBUG);
        log.warn("Hear Me");

        // Set to warn level
        log.setLevel(StdErrLog.LEVEL_WARN);
        log.warn("Cheer Me");

        // Validate Output
        String output = new String(test.toByteArray(),"UTF-8");
        System.err.print(output);
        Assert.assertThat(output,containsString("See Me"));
        Assert.assertThat(output,containsString("Hear Me"));
        Assert.assertThat(output,containsString("Cheer Me"));
    }

    /**
     * Tests StdErrLog.info() methods with level filtering.
     * <p>
     * Should only see INFO level messages when level is set to {@link StdErrLog#LEVEL_INFO} and below.
     */
    @Test
    public void testInfoFiltering() throws UnsupportedEncodingException
    {
        StdErrLog log = new StdErrLog(StdErrLogTest.class.getName());
        log.setHideStacks(true);

        ByteArrayOutputStream test = new ByteArrayOutputStream();
        PrintStream err = new PrintStream(test);
        log.setStdErrStream(err);

        // Normal/Default behavior
        log.info("I will not buy");

        // Level Debug
        log.setLevel(StdErrLog.LEVEL_DEBUG);
        log.info("this record");

        // Level All
        log.setLevel(StdErrLog.LEVEL_ALL);
        log.info("it is scratched.");

        // Level Warn
        log.setLevel(StdErrLog.LEVEL_WARN);
        log.info("sorry?");

        // Validate Output
        String output = new String(test.toByteArray(),"UTF-8");
        System.err.print(output);
        Assert.assertThat(output,containsString("I will not buy"));
        Assert.assertThat(output,containsString("this record"));
        Assert.assertThat(output,containsString("it is scratched."));
        Assert.assertThat(output,not(containsString("sorry?")));
    }

    /**
     * Tests StdErrLog.debug() methods with level filtering.
     * <p>
     * Should only see DEBUG level messages when level is set to {@link StdErrLog#LEVEL_DEBUG} and below.
     */
    @Test
    public void testDebugFiltering() throws UnsupportedEncodingException
    {
        StdErrLog log = new StdErrLog(StdErrLogTest.class.getName());
        log.setHideStacks(true);

        ByteArrayOutputStream test = new ByteArrayOutputStream();
        PrintStream err = new PrintStream(test);
        log.setStdErrStream(err);

        // Normal/Default behavior
        log.debug("Tobacconist");

        // Level Debug
        log.setLevel(StdErrLog.LEVEL_DEBUG);
        log.debug("my hovercraft is");

        // Level All
        log.setLevel(StdErrLog.LEVEL_ALL);
        log.debug("full of eels.");

        // Level Warn
        log.setLevel(StdErrLog.LEVEL_WARN);
        log.debug("what?");

        // Validate Output
        String output = new String(test.toByteArray(),"UTF-8");
        System.err.print(output);
        Assert.assertThat(output,not(containsString("Tobacconist")));
        Assert.assertThat(output,containsString("my hovercraft is"));
        Assert.assertThat(output,containsString("full of eels."));
        Assert.assertThat(output,not(containsString("what?")));
    }

    /**
     * Tests StdErrLog with {@link Logger#ignore(Throwable)} use.
     * <p>
     * Should only see IGNORED level messages when level is set to {@link StdErrLog#LEVEL_ALL}.
     */
    @Test
    public void testIgnores() throws UnsupportedEncodingException
    {
        StdErrLog log = new StdErrLog(StdErrLogTest.class.getName());
        log.setHideStacks(true);

        ByteArrayOutputStream test = new ByteArrayOutputStream();
        PrintStream err = new PrintStream(test);
        log.setStdErrStream(err);

        // Normal/Default behavior
        log.ignore(new Throwable("IGNORE ME"));

        // Show Ignored
        log.setLevel(StdErrLog.LEVEL_ALL);
        log.ignore(new Throwable("Don't ignore me"));

        // Set to Debug level
        log.setLevel(StdErrLog.LEVEL_DEBUG);
        log.ignore(new Throwable("Debug me"));

        // Validate Output
        String output = new String(test.toByteArray(),"UTF-8");
        System.err.print(output);
        Assert.assertThat(output,not(containsString("IGNORE ME")));
        Assert.assertThat(output,containsString("Don't ignore me"));
        Assert.assertThat(output,not(containsString("Debug me")));
    }
}
