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
import org.slf4j.event.Level;
import org.slf4j.helpers.MarkerIgnoringBase;

public class JettyLogger extends MarkerIgnoringBase implements Logger
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
    private int level;
    private JettyAppender appender;
    private boolean hideStacks = false;

    public JettyLogger(String name)
    {
        this.name = name;
        this.condensedName = JettyLoggerFactory.condensePackageString(name);
    }

    @Override
    public void debug(String msg)
    {
        if (isDebugEnabled())
        {
            getAppender().emit(asEvent(Level.DEBUG, msg));
        }
    }

    @Override
    public void debug(String format, Object arg)
    {
        if (isDebugEnabled())
        {
            getAppender().emit(asEvent(Level.DEBUG, format, arg));
        }
    }

    @Override
    public void debug(String format, Object arg1, Object arg2)
    {
        if (isDebugEnabled())
        {
            getAppender().emit(asEvent(Level.DEBUG, format, arg1, arg2));
        }
    }

    @Override
    public void debug(String format, Object... arguments)
    {
        if (isDebugEnabled())
        {
            getAppender().emit(asEvent(Level.DEBUG, format, arguments));
        }
    }

    @Override
    public void debug(String msg, Throwable throwable)
    {
        if (isDebugEnabled())
        {
            getAppender().emit(asEvent(Level.DEBUG, msg, throwable));
        }
    }

    @Override
    public void error(String msg)
    {
        if (isErrorEnabled())
        {
            getAppender().emit(asEvent(Level.ERROR, msg));
        }
    }

    @Override
    public void error(String format, Object arg)
    {
        if (isErrorEnabled())
        {
            getAppender().emit(asEvent(Level.ERROR, format, arg));
        }
    }

    @Override
    public void error(String format, Object arg1, Object arg2)
    {
        if (isErrorEnabled())
        {
            getAppender().emit(asEvent(Level.ERROR, format, arg1, arg2));
        }
    }

    @Override
    public void error(String format, Object... arguments)
    {
        if (isErrorEnabled())
        {
            getAppender().emit(asEvent(Level.ERROR, format, arguments));
        }
    }

    @Override
    public void error(String msg, Throwable throwable)
    {
        if (isErrorEnabled())
        {
            getAppender().emit(asEvent(Level.ERROR, msg, throwable));
        }
    }

    public JettyAppender getAppender()
    {
        return appender;
    }

    public void setAppender(JettyAppender appender)
    {
        this.appender = appender;
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
        jettyLoggerFactory.walkChildLoggers(this.getName(),
            (logger) -> logger.setLevel(lvlInt));
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
            getAppender().emit(asEvent(Level.INFO, msg));
        }
    }

    @Override
    public void info(String format, Object arg)
    {
        if (isInfoEnabled())
        {
            getAppender().emit(asEvent(Level.INFO, format, arg));
        }
    }

    @Override
    public void info(String format, Object arg1, Object arg2)
    {
        if (isInfoEnabled())
        {
            getAppender().emit(asEvent(Level.INFO, format, arg1, arg2));
        }
    }

    @Override
    public void info(String format, Object... arguments)
    {
        if (isInfoEnabled())
        {
            getAppender().emit(asEvent(Level.INFO, format, arguments));
        }
    }

    @Override
    public void info(String msg, Throwable throwable)
    {
        if (isInfoEnabled())
        {
            getAppender().emit(asEvent(Level.INFO, msg, throwable));
        }
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
            getAppender().emit(asEvent(Level.TRACE, msg));
        }
    }

    @Override
    public void trace(String format, Object arg)
    {
        if (isTraceEnabled())
        {
            getAppender().emit(asEvent(Level.TRACE, format, arg));
        }
    }

    @Override
    public void trace(String format, Object arg1, Object arg2)
    {
        if (isTraceEnabled())
        {
            getAppender().emit(asEvent(Level.TRACE, format, arg1, arg2));
        }
    }

    @Override
    public void trace(String format, Object... arguments)
    {
        if (isTraceEnabled())
        {
            getAppender().emit(asEvent(Level.TRACE, format, arguments));
        }
    }

    @Override
    public void trace(String msg, Throwable throwable)
    {
        if (isTraceEnabled())
        {
            getAppender().emit(asEvent(Level.TRACE, msg, throwable));
        }
    }

    @Override
    public void warn(String msg)
    {
        if (isWarnEnabled())
        {
            getAppender().emit(asEvent(Level.WARN, msg));
        }
    }

    @Override
    public void warn(String format, Object arg)
    {
        if (isWarnEnabled())
        {
            getAppender().emit(asEvent(Level.WARN, format, arg));
        }
    }

    @Override
    public void warn(String format, Object... arguments)
    {
        if (isWarnEnabled())
        {
            getAppender().emit(asEvent(Level.WARN, format, arguments));
        }
    }

    @Override
    public void warn(String format, Object arg1, Object arg2)
    {
        if (isWarnEnabled())
        {
            getAppender().emit(asEvent(Level.WARN, format, arg1, arg2));
        }
    }

    @Override
    public void warn(String msg, Throwable throwable)
    {
        if (isWarnEnabled())
        {
            getAppender().emit(asEvent(Level.WARN, msg, throwable));
        }
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

    @Override
    public String toString()
    {
        final StringBuilder sb = new StringBuilder();
        sb.append(JettyLogger.class.getSimpleName());
        sb.append(':').append(name);
        sb.append(":LEVEL=").append(levelToString(level));
        return sb.toString();
    }

    private JettyLoggingEvent asEvent(Level level, String msg)
    {
        String threadName = Thread.currentThread().getName();
        long timestamp = System.currentTimeMillis();
        return new JettyLoggingEvent(this, level, threadName, timestamp, msg, null);
    }

    private JettyLoggingEvent asEvent(Level level, String format, Object arg)
    {
        String threadName = Thread.currentThread().getName();
        long timestamp = System.currentTimeMillis();
        if (arg instanceof Throwable)
        {
            return new JettyLoggingEvent(this, level, threadName, timestamp, format, (Throwable)arg);
        }
        else
        {
            return new JettyLoggingEvent(this, level, threadName, timestamp, format, null, arg);
        }
    }

    private JettyLoggingEvent asEvent(Level level, String format, Object arg1, Object arg2)
    {
        String threadName = Thread.currentThread().getName();
        long timestamp = System.currentTimeMillis();
        if (arg2 instanceof Throwable)
        {
            return new JettyLoggingEvent(this, level, threadName, timestamp, format, (Throwable)arg2, arg1);
        }
        else
        {
            return new JettyLoggingEvent(this, level, threadName, timestamp, format, null, arg1, arg2);
        }
    }

    private JettyLoggingEvent asEvent(Level level, String format, Object... args)
    {
        String threadName = Thread.currentThread().getName();
        long timestamp = System.currentTimeMillis();
        if (args.length > 0)
        {
            int argsLen = args.length;
            if (args[argsLen - 1] instanceof Throwable)
            {
                // Final arg is a Throwable
                Throwable cause = (Throwable)args[argsLen - 1];
                return new JettyLoggingEvent(this, level, threadName, timestamp, format, cause, args);
            }
            else
            {
                return new JettyLoggingEvent(this, level, threadName, timestamp, format, null, args);
            }
        }
        else
        {
            return new JettyLoggingEvent(this, level, threadName, timestamp, format, null);
        }
    }

    private JettyLoggingEvent asEvent(Level level, String msg, Throwable throwable)
    {
        String threadName = Thread.currentThread().getName();
        long timestamp = System.currentTimeMillis();
        return new JettyLoggingEvent(this, level, threadName, timestamp, msg, throwable);
    }
}
