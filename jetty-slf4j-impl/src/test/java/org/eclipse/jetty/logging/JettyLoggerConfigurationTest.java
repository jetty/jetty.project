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

import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JettyLoggerConfigurationTest
{
    @Test
    public void testConfig()
    {
        Properties props = new Properties();
        props.setProperty(StdErrAppender.MESSAGE_ESCAPE_KEY, "false");
        props.setProperty(StdErrAppender.NAME_CONDENSE_KEY, "false");
        props.setProperty(StdErrAppender.THREAD_PADDING_KEY, "10");
        props.setProperty("com.mortbay.LEVEL", "WARN");
        props.setProperty("com.mortbay.STACKS", "false");

        JettyLoggerConfiguration config = new JettyLoggerConfiguration(props);
        StdErrAppender appender = new StdErrAppender(config);

        assertFalse(appender.isEscapedMessages());
        assertFalse(appender.isCondensedNames());
        assertEquals(appender.getThreadPadding(), 10);

        int level = config.getLevel("com.mortbay");
        assertEquals(Level.WARN.toInt(), level);

        boolean stacks = config.getHideStacks("com.mortbay.Foo");
        assertFalse(stacks);
    }

    @Test
    public void testGetLevelExact()
    {
        Properties props = new Properties();
        props.setProperty("com.mortbay.LEVEL", "WARN");
        JettyLoggerConfiguration config = new JettyLoggerConfiguration(props);
        int level = config.getLevel("com.mortbay");
        assertEquals(Level.WARN.toInt(), level);
    }

    @Test
    public void testGetLevelDotEnd()
    {
        Properties props = new Properties();
        props.setProperty("com.mortbay.LEVEL", "WARN");
        JettyLoggerConfiguration config = new JettyLoggerConfiguration(props);
        // extra trailing dot "."
        int level = config.getLevel("com.mortbay.");
        assertEquals(Level.WARN.toInt(), level);
    }

    @Test
    public void testGetLevelWithLevel()
    {
        Properties props = new Properties();
        props.setProperty("com.mortbay.LEVEL", "WARN");
        JettyLoggerConfiguration config = new JettyLoggerConfiguration(props);
        // asking for name with ".LEVEL"
        int level = config.getLevel("com.mortbay.Bar.LEVEL");
        assertEquals(Level.WARN.toInt(), level);
    }

    @Test
    public void testGetLevelChild()
    {
        Properties props = new Properties();
        props.setProperty("com.mortbay.LEVEL", "WARN");
        JettyLoggerConfiguration config = new JettyLoggerConfiguration(props);
        int level = config.getLevel("com.mortbay.Foo");
        assertEquals(Level.WARN.toInt(), level);
    }

    @Test
    public void testGetLevelDefault()
    {
        Properties props = new Properties();
        props.setProperty("com.mortbay.LEVEL", "WARN");
        JettyLoggerConfiguration config = new JettyLoggerConfiguration(props);
        // asking for name that isn't configured, returns default value
        int level = config.getLevel("org.eclipse.jetty");
        assertEquals(Level.INFO.toInt(), level);
    }

    @Test
    public void testGetHideStacksExact()
    {
        Properties props = new Properties();
        props.setProperty("com.mortbay.STACKS", "true");
        JettyLoggerConfiguration config = new JettyLoggerConfiguration(props);
        boolean val = config.getHideStacks("com.mortbay");
        assertTrue(val);
    }

    @Test
    public void testGetHideStacksDotEnd()
    {
        Properties props = new Properties();
        props.setProperty("com.mortbay.STACKS", "true");
        JettyLoggerConfiguration config = new JettyLoggerConfiguration(props);
        // extra trailing dot "."
        boolean val = config.getHideStacks("com.mortbay.");
        assertTrue(val);
    }

    @Test
    public void testGetHideStacksWithStacks()
    {
        Properties props = new Properties();
        props.setProperty("com.mortbay.STACKS", "true");
        JettyLoggerConfiguration config = new JettyLoggerConfiguration(props);
        // asking for name with ".STACKS"
        boolean val = config.getHideStacks("com.mortbay.Bar.STACKS");
        assertTrue(val);
    }

    @Test
    public void testGetHideStacksChild()
    {
        Properties props = new Properties();
        props.setProperty("com.mortbay.STACKS", "true");
        JettyLoggerConfiguration config = new JettyLoggerConfiguration(props);
        boolean val = config.getHideStacks("com.mortbay.Foo");
        assertTrue(val);
    }

    @Test
    public void testGetHideStacksDefault()
    {
        Properties props = new Properties();
        props.setProperty("com.mortbay.STACKS", "true");
        JettyLoggerConfiguration config = new JettyLoggerConfiguration(props);
        // asking for name that isn't configured, returns default value
        boolean val = config.getHideStacks("org.eclipse.jetty");
        assertFalse(val);
    }

    @Test
    public void testGetLoggingLevelBad()
    {
        Properties props = new Properties();
        props.setProperty("log.LEVEL", "WARN");
        props.setProperty("org.eclipse.jetty.bad.LEVEL", "EXPECTED_BAD_LEVEL");
        JettyLoggerConfiguration config = new JettyLoggerConfiguration(props);

        // Default Level (because of bad level value)
        assertEquals(Level.WARN.toInt(), config.getLevel("org.eclipse.jetty.bad"));
    }

    @Test
    public void testGetLoggingLevelLowercase()
    {
        Properties props = new Properties();
        props.setProperty("log.LEVEL", "warn");
        props.setProperty("org.eclipse.jetty.util.LEVEL", "info");
        JettyLoggerConfiguration config = new JettyLoggerConfiguration(props);

        // Default Level
        assertEquals(Level.WARN.toInt(), config.getLevel("org.eclipse.jetty"));
        // Specific Level
        assertEquals(Level.INFO.toInt(), config.getLevel("org.eclipse.jetty.util"));
    }

    @Test
    public void testGetLoggingLevelRoot()
    {
        Properties props = new Properties();
        props.setProperty("log.LEVEL", "DEBUG");
        JettyLoggerConfiguration config = new JettyLoggerConfiguration(props);

        // Default Levels
        assertEquals(Level.DEBUG.toInt(), config.getLevel(null));
        assertEquals(Level.DEBUG.toInt(), config.getLevel(""));
        assertEquals(Level.DEBUG.toInt(), config.getLevel("org.eclipse.jetty"));
        String name = JettyLoggerConfigurationTest.class.getName();
        assertEquals(Level.DEBUG.toInt(), config.getLevel(name), "Default Logging Level - " + name + " name");
    }

    @Test
    public void testGetLoggingLevelFQCN()
    {
        String name = JettyLoggerTest.class.getName();
        Properties props = new Properties();
        props.setProperty(name + ".LEVEL", "ALL");
        JettyLoggerConfiguration config = new JettyLoggerConfiguration(props);

        // Default Levels
        assertEquals(Level.INFO.toInt(), config.getLevel(null));
        assertEquals(Level.INFO.toInt(), config.getLevel(""));
        assertEquals(Level.INFO.toInt(), config.getLevel("org.eclipse.jetty"));

        // Specified Level
        assertEquals(JettyLogger.ALL, config.getLevel(name));
    }

    @Test
    public void testGetLoggingLevelUtilLevel()
    {
        Properties props = new Properties();
        props.setProperty("org.eclipse.jetty.util.LEVEL", "DEBUG");
        JettyLoggerConfiguration config = new JettyLoggerConfiguration(props);

        // Default Levels
        assertEquals(Level.INFO.toInt(), config.getLevel(null));
        assertEquals(Level.INFO.toInt(), config.getLevel(""));
        assertEquals(Level.INFO.toInt(), config.getLevel("org.eclipse.jetty"));
        assertEquals(Level.INFO.toInt(), config.getLevel("org.eclipse.jetty.server.BogusObject"));
        assertEquals(Level.INFO.toInt(), config.getLevel(JettyLoggerConfigurationTest.class.getName()));

        // Configured Level
        assertEquals(Level.DEBUG.toInt(), config.getLevel("org.eclipse.jetty.util.Bogus"));
        assertEquals(Level.DEBUG.toInt(), config.getLevel("org.eclipse.jetty.util"));
        assertEquals(Level.DEBUG.toInt(), config.getLevel("org.eclipse.jetty.util.resource.PathResource"));
    }

    @Test
    public void testGetLoggingLevelMixedLevels()
    {
        Properties props = new Properties();
        props.setProperty("log.LEVEL", "DEBUG");
        props.setProperty("org.eclipse.jetty.util.LEVEL", "WARN");
        props.setProperty("org.eclipse.jetty.util.ConcurrentHashMap.LEVEL", "ALL");

        JettyLoggerConfiguration config = new JettyLoggerConfiguration(props);

        // Default Levels
        assertEquals(Level.DEBUG.toInt(), config.getLevel(null));
        assertEquals(Level.DEBUG.toInt(), config.getLevel(""));
        assertEquals(Level.DEBUG.toInt(), config.getLevel("org.eclipse.jetty"));
        assertEquals(Level.DEBUG.toInt(), config.getLevel("org.eclipse.jetty.server.BogusObject"));
        assertEquals(Level.DEBUG.toInt(), config.getLevel(JettyLoggerConfigurationTest.class.getName()));

        // Configured Level
        assertEquals(Level.WARN.toInt(), config.getLevel("org.eclipse.jetty.util.MagicUtil"));
        assertEquals(Level.WARN.toInt(), config.getLevel("org.eclipse.jetty.util"));
        assertEquals(Level.WARN.toInt(), config.getLevel("org.eclipse.jetty.util.resource.PathResource"));

        assertEquals(JettyLogger.ALL, config.getLevel("org.eclipse.jetty.util.ConcurrentHashMap"));
    }
}
