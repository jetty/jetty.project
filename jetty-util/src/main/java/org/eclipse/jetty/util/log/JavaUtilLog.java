//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import java.util.logging.Level;

/**
 * <p>
 * Implementation of Jetty {@link Logger} based on {@link java.util.logging.Logger}.
 * </p>
 *
 * <p>
 * You can also set the logger level using <a href="http://java.sun.com/j2se/1.5.0/docs/guide/logging/overview.html">
 * standard java.util.logging configuration</a>.
 * </p>
 */
public class JavaUtilLog extends AbstractLogger
{
    private Level configuredLevel;
    private java.util.logging.Logger _logger;

    public JavaUtilLog()
    {
        this("org.eclipse.jetty.util.log");
    }

    public JavaUtilLog(String name)
    {
        _logger = java.util.logging.Logger.getLogger(name);
        if (Boolean.parseBoolean(Log.__props.getProperty("org.eclipse.jetty.util.log.DEBUG", "false")))
        {
            _logger.setLevel(Level.FINE);
        }
        configuredLevel = _logger.getLevel();
    }

    public String getName()
    {
        return _logger.getName();
    }

    public void warn(String msg, Object... args)
    {
        _logger.log(Level.WARNING, format(msg, args));
    }

    public void warn(Throwable thrown)
    {
        warn("", thrown);
    }

    public void warn(String msg, Throwable thrown)
    {
        _logger.log(Level.WARNING, msg, thrown);
    }

    public void info(String msg, Object... args)
    {
        _logger.log(Level.INFO, format(msg, args));
    }

    public void info(Throwable thrown)
    {
        info("", thrown);
    }

    public void info(String msg, Throwable thrown)
    {
        _logger.log(Level.INFO, msg, thrown);
    }

    public boolean isDebugEnabled()
    {
        return _logger.isLoggable(Level.FINE);
    }

    public void setDebugEnabled(boolean enabled)
    {
        if (enabled)
        {
            configuredLevel = _logger.getLevel();
            _logger.setLevel(Level.FINE);
        }
        else
        {
            _logger.setLevel(configuredLevel);
        }
    }

    public void debug(String msg, Object... args)
    {
        _logger.log(Level.FINE, format(msg, args));
    }

    public void debug(Throwable thrown)
    {
        debug("", thrown);
    }

    public void debug(String msg, Throwable thrown)
    {
        _logger.log(Level.FINE, msg, thrown);
    }

    /**
     * Create a Child Logger of this Logger.
     */
    protected Logger newLogger(String fullname)
    {
        return new JavaUtilLog(fullname);
    }

    public void ignore(Throwable ignored)
    {
        if (Log.isIgnored())
        {
            warn(Log.IGNORED, ignored);
        }
    }

    private String format(String msg, Object... args)
    {
        msg = String.valueOf(msg); // Avoids NPE
        String braces = "{}";
        StringBuilder builder = new StringBuilder();
        int start = 0;
        for (Object arg : args)
        {
            int bracesIndex = msg.indexOf(braces, start);
            if (bracesIndex < 0)
            {
                builder.append(msg.substring(start));
                builder.append(" ");
                builder.append(arg);
                start = msg.length();
            }
            else
            {
                builder.append(msg.substring(start, bracesIndex));
                builder.append(String.valueOf(arg));
                start = bracesIndex + braces.length();
            }
        }
        builder.append(msg.substring(start));
        return builder.toString();
    }
}
