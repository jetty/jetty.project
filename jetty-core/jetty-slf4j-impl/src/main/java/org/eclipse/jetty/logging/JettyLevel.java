//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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

import java.util.Locale;

import org.slf4j.event.Level;

/**
 * The Jetty logging level constants
 */
public enum JettyLevel
{
    // Intentionally sorted incrementally by level int

    /**
     * Activate all log levels
     */
    ALL(Level.TRACE.toInt() - 10),
    /**
     * Trace level
     */
    TRACE(Level.TRACE),
    /**
     * Debug level
     */
    DEBUG(Level.DEBUG),
    /**
     * Info level
     */
    INFO(Level.INFO),
    /**
     * Warning level
     */
    WARN(Level.WARN),
    /**
     * Error level
     */
    ERROR(Level.ERROR),
    /**
     * Disable logging
     */
    OFF(Level.ERROR.toInt() + 1);

    private final Level level;
    private final int levelInt;

    JettyLevel(Level level)
    {
        this.level = level;
        this.levelInt = level.toInt();
    }

    JettyLevel(int i)
    {
        this.level = null;
        this.levelInt = i;
    }

    /**
     * <p>fromLevel</p>
     * @param slf4jLevel the slf4j {@link Level}
     * @return the correct {@link JettyLevel}
     */
    public static JettyLevel fromLevel(Level slf4jLevel)
    {
        for (JettyLevel level : JettyLevel.values())
        {
            if (slf4jLevel.toInt() == level.levelInt)
                return level;
        }
        return OFF;
    }

    /**
     * <p>toInt</p>
     * @return the level as {@code int}
     */
    public int toInt()
    {
        return levelInt;
    }

    /**
     * <p>toLevel</p>
     * @return the corresponding slf4j {@link Level}
     */
    public Level toLevel()
    {
        return level;
    }

    /**
     * Tests that a provided level is included by the level value of this level.
     *
     * @param testLevel the level to test against.
     * @return true if includes this includes the test level.
     */
    public boolean includes(JettyLevel testLevel)
    {
        return (this.levelInt <= testLevel.levelInt);
    }

    @Override
    public String toString()
    {
        return name();
    }

    /**
     * <p>intToLevel</p>
     * @param levelInt the level as {@code int}
     * @return the corresponding {@link JettyLevel}
     */
    public static JettyLevel intToLevel(int levelInt)
    {
        for (JettyLevel level : JettyLevel.values())
        {
            if (levelInt <= level.levelInt)
                return level;
        }
        return OFF;
    }

    /**
     * <p>strToLevel</p>
     * @param levelStr level as String
     * @return the corresponding {@link JettyLevel}
     */
    public static JettyLevel strToLevel(String levelStr)
    {
        if (levelStr == null)
        {
            return null;
        }

        String levelName = levelStr.trim().toUpperCase(Locale.ENGLISH);
        for (JettyLevel level : JettyLevel.values())
        {
            if (level.name().equals(levelName))
                return level;
        }

        return null;
    }
}
