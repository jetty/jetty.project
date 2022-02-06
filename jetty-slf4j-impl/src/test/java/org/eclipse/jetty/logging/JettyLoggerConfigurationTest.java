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
        props.setProperty(StdErrAppender.MESSAGE_ALIGN_KEY, "10");
        props.setProperty("com.mortbay.LEVEL", "WARN");
        props.setProperty("com.mortbay.STACKS", "false");

        JettyLoggerConfiguration config = new JettyLoggerConfiguration(props);
        StdErrAppender appender = new StdErrAppender(config);

        assertFalse(appender.isEscapedMessages());
        assertFalse(appender.isCondensedNames());
        assertEquals(appender.getMessageAlignColumn(), 10);

        JettyLevel level = config.getLevel("com.mortbay");
        assertEquals(JettyLevel.WARN, level);

        boolean stacks = config.getHideStacks("com.mortbay.Foo");
        assertFalse(stacks);
    }

    @Test
    public void testGetLevelExact()
    {
        Properties props = new Properties();
        props.setProperty("com.mortbay.LEVEL", "WARN");
        JettyLoggerConfiguration config = new JettyLoggerConfiguration(props);
        JettyLevel level = config.getLevel("com.mortbay");
        assertEquals(JettyLevel.WARN, level);
    }

    @Test
    public void testGetLevelDotEnd()
    {
        Properties props = new Properties();
        props.setProperty("com.mortbay.LEVEL", "WARN");
        JettyLoggerConfiguration config = new JettyLoggerConfiguration(props);
        // extra trailing dot "."
        JettyLevel level = config.getLevel("com.mortbay.");
        assertEquals(JettyLevel.WARN, level);
    }

    @Test
    public void testGetLevelWithLevel()
    {
        Properties props = new Properties();
        props.setProperty("com.mortbay.LEVEL", "WARN");
        JettyLoggerConfiguration config = new JettyLoggerConfiguration(props);
        // asking for name with ".LEVEL"
        JettyLevel level = config.getLevel("com.mortbay.Bar.LEVEL");
        assertEquals(JettyLevel.WARN, level);
    }

    @Test
    public void testGetLevelChild()
    {
        Properties props = new Properties();
        props.setProperty("com.mortbay.LEVEL", "WARN");
        JettyLoggerConfiguration config = new JettyLoggerConfiguration(props);
        JettyLevel level = config.getLevel("com.mortbay.Foo");
        assertEquals(JettyLevel.WARN, level);
    }

    @Test
    public void testGetLevelDefault()
    {
        Properties props = new Properties();
        props.setProperty("com.mortbay.LEVEL", "WARN");
        JettyLoggerConfiguration config = new JettyLoggerConfiguration(props);
        // asking for name that isn't configured, returns default value
        JettyLevel level = config.getLevel("org.eclipse.jetty");
        assertEquals(JettyLevel.INFO, level);
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
        props.setProperty("ROOT.LEVEL", "WARN");
        props.setProperty("org.eclipse.jetty.bad.LEVEL", "EXPECTED_BAD_LEVEL");
        JettyLoggerConfiguration config = new JettyLoggerConfiguration(props);

        // Default Level (because of bad level value)
        assertEquals(JettyLevel.WARN, config.getLevel("org.eclipse.jetty.bad"));
    }

    @Test
    public void testGetLoggingLevelLowercase()
    {
        Properties props = new Properties();
        props.setProperty("ROOT.LEVEL", "warn");
        props.setProperty("org.eclipse.jetty.util.LEVEL", "info");
        JettyLoggerConfiguration config = new JettyLoggerConfiguration(props);

        // Default Level
        assertEquals(JettyLevel.WARN, config.getLevel("org.eclipse.jetty"));
        // Specific Level
        assertEquals(JettyLevel.INFO, config.getLevel("org.eclipse.jetty.util"));
    }

    @Test
    public void testGetLoggingLevelRoot()
    {
        Properties props = new Properties();
        props.setProperty("ROOT.LEVEL", "DEBUG");
        JettyLoggerConfiguration config = new JettyLoggerConfiguration(props);

        // Default Levels
        assertEquals(JettyLevel.DEBUG, config.getLevel(null));
        assertEquals(JettyLevel.DEBUG, config.getLevel(""));
        assertEquals(JettyLevel.DEBUG, config.getLevel("org.eclipse.jetty"));
        String name = JettyLoggerConfigurationTest.class.getName();
        assertEquals(JettyLevel.DEBUG, config.getLevel(name), "Default Logging Level - " + name + " name");
    }

    @Test
    public void testGetLoggingLevelFQCN()
    {
        String name = JettyLoggerTest.class.getName();
        Properties props = new Properties();
        props.setProperty(name + ".LEVEL", "ALL");
        JettyLoggerConfiguration config = new JettyLoggerConfiguration(props);

        // Default Levels
        assertEquals(JettyLevel.INFO, config.getLevel(null));
        assertEquals(JettyLevel.INFO, config.getLevel(""));
        assertEquals(JettyLevel.INFO, config.getLevel("org.eclipse.jetty"));

        // Specified Level
        assertEquals(JettyLevel.ALL, config.getLevel(name));
    }

    @Test
    public void testGetLoggingLevelUtilLevel()
    {
        Properties props = new Properties();
        props.setProperty("org.eclipse.jetty.util.LEVEL", "DEBUG");
        JettyLoggerConfiguration config = new JettyLoggerConfiguration(props);

        // Default Levels
        assertEquals(JettyLevel.INFO, config.getLevel(null));
        assertEquals(JettyLevel.INFO, config.getLevel(""));
        assertEquals(JettyLevel.INFO, config.getLevel("org.eclipse.jetty"));
        assertEquals(JettyLevel.INFO, config.getLevel("org.eclipse.jetty.server.BogusObject"));
        assertEquals(JettyLevel.INFO, config.getLevel(JettyLoggerConfigurationTest.class.getName()));

        // Configured Level
        assertEquals(JettyLevel.DEBUG, config.getLevel("org.eclipse.jetty.util.Bogus"));
        assertEquals(JettyLevel.DEBUG, config.getLevel("org.eclipse.jetty.util"));
        assertEquals(JettyLevel.DEBUG, config.getLevel("org.eclipse.jetty.util.resource.PathResource"));
    }

    @Test
    public void testGetLoggingLevelMixedLevels()
    {
        Properties props = new Properties();
        props.setProperty("ROOT.LEVEL", "DEBUG");
        props.setProperty("org.eclipse.jetty.util.LEVEL", "WARN");
        props.setProperty("org.eclipse.jetty.util.ConcurrentHashMap.LEVEL", "ALL");

        JettyLoggerConfiguration config = new JettyLoggerConfiguration(props);

        // Default Levels
        assertEquals(JettyLevel.DEBUG, config.getLevel(null));
        assertEquals(JettyLevel.DEBUG, config.getLevel(""));
        assertEquals(JettyLevel.DEBUG, config.getLevel("org.eclipse.jetty"));
        assertEquals(JettyLevel.DEBUG, config.getLevel("org.eclipse.jetty.server.BogusObject"));
        assertEquals(JettyLevel.DEBUG, config.getLevel(JettyLoggerConfigurationTest.class.getName()));

        // Configured Level
        assertEquals(JettyLevel.WARN, config.getLevel("org.eclipse.jetty.util.MagicUtil"));
        assertEquals(JettyLevel.WARN, config.getLevel("org.eclipse.jetty.util"));
        assertEquals(JettyLevel.WARN, config.getLevel("org.eclipse.jetty.util.resource.PathResource"));

        assertEquals(JettyLevel.ALL, config.getLevel("org.eclipse.jetty.util.ConcurrentHashMap"));
    }
}
