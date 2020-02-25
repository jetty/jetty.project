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

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.event.LoggingEvent;
import org.slf4j.helpers.SubstituteLogger;
import org.slf4j.spi.LocationAwareLogger;

public class JettyLogger implements LocationAwareLogger, Logger
{
    /**
     * The Level to set if you want this logger to be "OFF"
     */
    public static final int OFF = 999;
    /**
     * The Level to set if you want this logger to show all events from all levels.
     */
    public static final int ALL = -1;

    private final String name;
    private final String condensedName;
    private final JettyAppender appender;
    private int level;
    private boolean hideStacks = false;

    /**
     * Entry point for slf4j and it's {@link SubstituteLogger}
     */
    @SuppressWarnings("unused")
    public JettyLogger(String name, JettyAppender appender)
    {
        this.name = name;
        this.condensedName = JettyLoggerFactory.condensePackageString(name);
        this.appender = appender;
    }

    @Override
    public void debug(String msg)
    {
        if (isDebugEnabled())
        {
            long timestamp = System.currentTimeMillis();
            String threadName = Thread.currentThread().getName();
            getAppender().emit(this, Level.DEBUG, timestamp, threadName, msg);
        }
    }

    @Override
    public void debug(String format, Object arg)
    {
        if (isDebugEnabled())
        {
            long timestamp = System.currentTimeMillis();
            String threadName = Thread.currentThread().getName();
            getAppender().emit(this, Level.DEBUG, timestamp, threadName, format, arg);
        }
    }

    @Override
    public void debug(String format, Object arg1, Object arg2)
    {
        if (isDebugEnabled())
        {
            long timestamp = System.currentTimeMillis();
            String threadName = Thread.currentThread().getName();
            getAppender().emit(this, Level.DEBUG, timestamp, threadName, format, arg1, arg2);
        }
    }

    @Override
    public void debug(String format, Object... arguments)
    {
        if (isDebugEnabled())
        {
            long timestamp = System.currentTimeMillis();
            String threadName = Thread.currentThread().getName();
            getAppender().emit(this, Level.DEBUG, timestamp, threadName, format, arguments);
        }
    }

    @Override
    public void debug(String msg, Throwable throwable)
    {
        if (isDebugEnabled())
        {
            long timestamp = System.currentTimeMillis();
            String threadName = Thread.currentThread().getName();
            getAppender().emit(this, Level.INFO, timestamp, threadName, throwable, msg);
        }
    }

    @Override
    public boolean isDebugEnabled(Marker marker)
    {
        return isDebugEnabled();
    }

    @Override
    public void debug(Marker marker, String msg)
    {
        // TODO: do we want to support org.sfl4j.Marker?
        debug(msg);
    }

    @Override
    public void debug(Marker marker, String format, Object arg)
    {
        // TODO: do we want to support org.sfl4j.Marker?
        debug(format, arg);
    }

    @Override
    public void debug(Marker marker, String format, Object arg1, Object arg2)
    {
        // TODO: do we want to support org.sfl4j.Marker?
        debug(format, arg1, arg2);
    }

    @Override
    public void debug(Marker marker, String format, Object... arguments)
    {
        // TODO: do we want to support org.sfl4j.Marker?
        debug(format, arguments);
    }

    @Override
    public void debug(Marker marker, String msg, Throwable t)
    {
        // TODO: do we want to support org.sfl4j.Marker?
        debug(msg, t);
    }

    @Override
    public void error(String msg)
    {
        if (isErrorEnabled())
        {
            long timestamp = System.currentTimeMillis();
            String threadName = Thread.currentThread().getName();
            getAppender().emit(this, Level.ERROR, timestamp, threadName, msg);
        }
    }

    @Override
    public void error(String format, Object arg)
    {
        if (isErrorEnabled())
        {
            long timestamp = System.currentTimeMillis();
            String threadName = Thread.currentThread().getName();
            getAppender().emit(this, Level.ERROR, timestamp, threadName, format, arg);
        }
    }

    @Override
    public void error(String format, Object arg1, Object arg2)
    {
        if (isErrorEnabled())
        {
            long timestamp = System.currentTimeMillis();
            String threadName = Thread.currentThread().getName();
            getAppender().emit(this, Level.ERROR, timestamp, threadName, format, arg1, arg2);
        }
    }

    @Override
    public void error(String format, Object... arguments)
    {
        if (isErrorEnabled())
        {
            long timestamp = System.currentTimeMillis();
            String threadName = Thread.currentThread().getName();
            getAppender().emit(this, Level.ERROR, timestamp, threadName, format, arguments);
        }
    }

    @Override
    public void error(String msg, Throwable throwable)
    {
        if (isErrorEnabled())
        {
            long timestamp = System.currentTimeMillis();
            String threadName = Thread.currentThread().getName();
            getAppender().emit(this, Level.ERROR, timestamp, threadName, throwable, msg);
        }
    }

    @Override
    public boolean isErrorEnabled(Marker marker)
    {
        return isErrorEnabled();
    }

    @Override
    public void error(Marker marker, String msg)
    {
        // TODO: do we want to support org.sfl4j.Marker?
        error(msg);
    }

    @Override
    public void error(Marker marker, String format, Object arg)
    {
        // TODO: do we want to support org.sfl4j.Marker?
        error(format, arg);
    }

    @Override
    public void error(Marker marker, String format, Object arg1, Object arg2)
    {
        // TODO: do we want to support org.sfl4j.Marker?
        error(format, arg1, arg2);
    }

    @Override
    public void error(Marker marker, String format, Object... arguments)
    {
        // TODO: do we want to support org.sfl4j.Marker?
        error(format, arguments);
    }

    @Override
    public void error(Marker marker, String msg, Throwable t)
    {
        // TODO: do we want to support org.sfl4j.Marker?
        error(msg, t);
    }

    public JettyAppender getAppender()
    {
        return appender;
    }

    /**
     * Entry point for {@link LocationAwareLogger}
     */
    @Override
    public void log(Marker marker, String fqcn, int levelInt, String message, Object[] argArray, Throwable throwable)
    {
        if (this.level <= levelInt)
        {
            long timestamp = System.currentTimeMillis();
            String threadName = Thread.currentThread().getName();
            getAppender().emit(this, intToLevel(levelInt), timestamp, threadName, throwable, message, argArray);
        }
    }

    /**
     * Dynamic (via Reflection) entry point for {@link SubstituteLogger} usage.
     *
     * @param event the logging event
     */
    @SuppressWarnings("unused")
    public void log(LoggingEvent event)
    {
        // TODO: do we want to support org.sfl4j.Marker?
        // TODO: do we want to support org.sfl4j.even.KeyValuePair?
        getAppender().emit(this, event.getLevel(), event.getTimeStamp(), event.getThreadName(), event.getThrowable(), event.getMessage(), event.getArgumentArray());
    }

    public String getCondensedName()
    {
        return condensedName;
    }

    public int getLevel()
    {
        return level;
    }

    public void setLevel(Level level)
    {
        Objects.requireNonNull(level, "Level");
        setLevel(level.toInt());
    }

    public void setLevel(int lvlInt)
    {
        this.level = lvlInt;

        // apply setLevel to children too.
        JettyLoggerFactory jettyLoggerFactory = JettyLoggerFactory.getLoggerFactory();
        jettyLoggerFactory.walkChildLoggers(this.getName(), (logger) -> logger.setLevel(lvlInt));
    }

    @Override
    public String getName()
    {
        return name;
    }

    @Override
    public void info(String msg)
    {
        if (isInfoEnabled())
        {
            long timestamp = System.currentTimeMillis();
            String threadName = Thread.currentThread().getName();
            getAppender().emit(this, Level.INFO, timestamp, threadName, msg);
        }
    }

    @Override
    public void info(String format, Object arg)
    {
        if (isInfoEnabled())
        {
            long timestamp = System.currentTimeMillis();
            String threadName = Thread.currentThread().getName();
            getAppender().emit(this, Level.INFO, timestamp, threadName, format, arg);
        }
    }

    @Override
    public void info(String format, Object arg1, Object arg2)
    {
        if (isInfoEnabled())
        {
            long timestamp = System.currentTimeMillis();
            String threadName = Thread.currentThread().getName();
            getAppender().emit(this, Level.INFO, timestamp, threadName, format, arg1, arg2);
        }
    }

    @Override
    public void info(String format, Object... arguments)
    {
        if (isInfoEnabled())
        {
            long timestamp = System.currentTimeMillis();
            String threadName = Thread.currentThread().getName();
            getAppender().emit(this, Level.INFO, timestamp, threadName, format, arguments);
        }
    }

    @Override
    public void info(String msg, Throwable throwable)
    {
        if (isInfoEnabled())
        {
            long timestamp = System.currentTimeMillis();
            String threadName = Thread.currentThread().getName();
            getAppender().emit(this, Level.INFO, timestamp, threadName, throwable, msg);
        }
    }

    @Override
    public boolean isInfoEnabled(Marker marker)
    {
        return isInfoEnabled();
    }

    @Override
    public void info(Marker marker, String msg)
    {
        // TODO: do we want to support org.sfl4j.Marker?
        info(msg);
    }

    @Override
    public void info(Marker marker, String format, Object arg)
    {
        // TODO: do we want to support org.sfl4j.Marker?
        info(format, arg);
    }

    @Override
    public void info(Marker marker, String format, Object arg1, Object arg2)
    {
        // TODO: do we want to support org.sfl4j.Marker?
        info(format, arg1, arg2);
    }

    @Override
    public void info(Marker marker, String format, Object... arguments)
    {
        // TODO: do we want to support org.sfl4j.Marker?
        info(format, arguments);
    }

    @Override
    public void info(Marker marker, String msg, Throwable t)
    {
        // TODO: do we want to support org.sfl4j.Marker?
        info(msg, t);
    }

    @Override
    public boolean isDebugEnabled()
    {
        return level <= Level.DEBUG.toInt();
    }

    @Override
    public boolean isErrorEnabled()
    {
        return level <= Level.ERROR.toInt();
    }

    public boolean isHideStacks()
    {
        return hideStacks;
    }

    public void setHideStacks(boolean hideStacks)
    {
        this.hideStacks = hideStacks;
    }

    @Override
    public boolean isInfoEnabled()
    {
        return level <= Level.INFO.toInt();
    }

    @Override
    public boolean isTraceEnabled()
    {
        return level <= Level.TRACE.toInt();
    }

    @Override
    public boolean isWarnEnabled()
    {
        return level <= Level.WARN.toInt();
    }

    @Override
    public void trace(String msg)
    {
        if (isTraceEnabled())
        {
            long timestamp = System.currentTimeMillis();
            String threadName = Thread.currentThread().getName();
            getAppender().emit(this, Level.TRACE, timestamp, threadName, msg);
        }
    }

    @Override
    public void trace(String format, Object arg)
    {
        if (isTraceEnabled())
        {
            long timestamp = System.currentTimeMillis();
            String threadName = Thread.currentThread().getName();
            getAppender().emit(this, Level.TRACE, timestamp, threadName, format, arg);
        }
    }

    @Override
    public void trace(String format, Object arg1, Object arg2)
    {
        if (isTraceEnabled())
        {
            long timestamp = System.currentTimeMillis();
            String threadName = Thread.currentThread().getName();
            getAppender().emit(this, Level.TRACE, timestamp, threadName, format, arg1, arg2);
        }
    }

    @Override
    public void trace(String format, Object... arguments)
    {
        if (isTraceEnabled())
        {
            long timestamp = System.currentTimeMillis();
            String threadName = Thread.currentThread().getName();
            getAppender().emit(this, Level.TRACE, timestamp, threadName, format, arguments);
        }
    }

    @Override
    public void trace(String msg, Throwable throwable)
    {
        if (isTraceEnabled())
        {
            long timestamp = System.currentTimeMillis();
            String threadName = Thread.currentThread().getName();
            getAppender().emit(this, Level.TRACE, timestamp, threadName, throwable, msg);
        }
    }

    @Override
    public boolean isTraceEnabled(Marker marker)
    {
        return isTraceEnabled();
    }

    @Override
    public void trace(Marker marker, String msg)
    {
        // TODO: do we want to support org.sfl4j.Marker?
        trace(msg);
    }

    @Override
    public void trace(Marker marker, String format, Object arg)
    {
        // TODO: do we want to support org.sfl4j.Marker?
        trace(format, arg);
    }

    @Override
    public void trace(Marker marker, String format, Object arg1, Object arg2)
    {
        // TODO: do we want to support org.sfl4j.Marker?
        trace(format, arg1, arg2);
    }

    @Override
    public void trace(Marker marker, String format, Object... argArray)
    {
        // TODO: do we want to support org.sfl4j.Marker?
        trace(format, argArray);
    }

    @Override
    public void trace(Marker marker, String msg, Throwable t)
    {
        // TODO: do we want to support org.sfl4j.Marker?
        trace(msg, t);
    }

    @Override
    public void warn(String msg)
    {
        if (isWarnEnabled())
        {
            long timestamp = System.currentTimeMillis();
            String threadName = Thread.currentThread().getName();
            getAppender().emit(this, Level.WARN, timestamp, threadName, msg);
        }
    }

    @Override
    public void warn(String format, Object arg)
    {
        if (isWarnEnabled())
        {
            long timestamp = System.currentTimeMillis();
            String threadName = Thread.currentThread().getName();
            getAppender().emit(this, Level.WARN, timestamp, threadName, format, arg);
        }
    }

    @Override
    public void warn(String format, Object... arguments)
    {
        if (isWarnEnabled())
        {
            long timestamp = System.currentTimeMillis();
            String threadName = Thread.currentThread().getName();
            getAppender().emit(this, Level.WARN, timestamp, threadName, format, arguments);
        }
    }

    @Override
    public void warn(String format, Object arg1, Object arg2)
    {
        if (isWarnEnabled())
        {
            long timestamp = System.currentTimeMillis();
            String threadName = Thread.currentThread().getName();
            getAppender().emit(this, Level.WARN, timestamp, threadName, format, arg1, arg2);
        }
    }

    @Override
    public void warn(String msg, Throwable throwable)
    {
        if (isWarnEnabled())
        {
            long timestamp = System.currentTimeMillis();
            String threadName = Thread.currentThread().getName();
            getAppender().emit(this, Level.WARN, timestamp, threadName, throwable, msg);
        }
    }

    @Override
    public boolean isWarnEnabled(Marker marker)
    {
        return isWarnEnabled();
    }

    @Override
    public void warn(Marker marker, String msg)
    {
        // TODO: do we want to support org.sfl4j.Marker?
        warn(msg);
    }

    @Override
    public void warn(Marker marker, String format, Object arg)
    {
        // TODO: do we want to support org.sfl4j.Marker?
        warn(format, arg);
    }

    @Override
    public void warn(Marker marker, String format, Object arg1, Object arg2)
    {
        // TODO: do we want to support org.sfl4j.Marker?
        warn(format, arg1, arg2);
    }

    @Override
    public void warn(Marker marker, String format, Object... arguments)
    {
        // TODO: do we want to support org.sfl4j.Marker?
        warn(format, arguments);
    }

    @Override
    public void warn(Marker marker, String msg, Throwable t)
    {
        // TODO: do we want to support org.sfl4j.Marker?
        warn(msg, t);
    }

    public static Level intToLevel(int level)
    {
        if (level >= JettyLogger.OFF)
            return Level.ERROR;
        if (level >= Level.ERROR.toInt())
            return Level.ERROR;
        if (level >= Level.WARN.toInt())
            return Level.WARN;
        if (level >= Level.INFO.toInt())
            return Level.INFO;
        if (level >= Level.DEBUG.toInt())
            return Level.DEBUG;
        if (level >= Level.TRACE.toInt())
            return Level.TRACE;
        return Level.TRACE; // everything else
    }

    public static String levelToString(int level)
    {
        if (level >= JettyLogger.OFF)
            return "OFF";
        if (level >= Level.ERROR.toInt())
            return "ERROR";
        if (level >= Level.WARN.toInt())
            return "WARN";
        if (level >= Level.INFO.toInt())
            return "INFO";
        if (level >= Level.DEBUG.toInt())
            return "DEBUG";
        if (level >= Level.TRACE.toInt())
            return "TRACE";
        return "OFF"; // everything else
    }

    @SuppressWarnings("StringBufferReplaceableByString")
    @Override
    public String toString()
    {
        final StringBuilder sb = new StringBuilder();
        sb.append(JettyLogger.class.getSimpleName());
        sb.append(':').append(name);
        sb.append(":LEVEL=").append(levelToString(level));
        return sb.toString();
    }
}
