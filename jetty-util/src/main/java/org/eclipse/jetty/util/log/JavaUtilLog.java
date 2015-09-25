//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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
import java.util.logging.LogRecord;

/**
 * <p>
 * Implementation of Jetty {@link Logger} based on {@link java.util.logging.Logger}.
 * </p>
 *
 * <p>
 * You can also set the logger level using <a href="http://docs.oracle.com/javase/8/docs/api/java/util/logging/package-summary.html">
 * standard java.util.logging configuration</a>.
 * </p>
 * 
 * Configuration Properties:
 * <dl>
 *   <dt>org.eclipse.jetty.util.log.javautil.SOURCE=(true|false)</dt>
 *   <dd>Set the LogRecord source class and method for JavaUtilLog.<br>
 *   Default: true
 *   </dd>
 *   <dt>org.eclipse.jetty.util.log.SOURCE=(true|false)</dt>
 *   <dd>Set the LogRecord source class and method for all Loggers.<br>
 *   Default: depends on Logger class
 *   </dd>
 * </dl>
 */
public class JavaUtilLog extends AbstractLogger
{
    private final static String THIS_CLASS= JavaUtilLog.class.getName();
    private final static boolean __source = 
            Boolean.parseBoolean(Log.__props.getProperty("org.eclipse.jetty.util.log.SOURCE",
            Log.__props.getProperty("org.eclipse.jetty.util.log.javautil.SOURCE","true")));
    
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

    protected void log(Level level,String msg,Throwable thrown)
    {
        LogRecord record = new LogRecord(level,msg);
        if (thrown!=null)
            record.setThrown(thrown);
        record.setLoggerName(_logger.getName());
        if (__source)
        {
            StackTraceElement[] stack = new Throwable().getStackTrace();
            for (int i=0;i<stack.length;i++)
            {
                StackTraceElement e=stack[i];
                if (!e.getClassName().equals(THIS_CLASS))
                {
                    record.setSourceClassName(e.getClassName());
                    record.setSourceMethodName(e.getMethodName());
                    break;
                }
            }
        }
        _logger.log(record);
    }
    
    public void warn(String msg, Object... args)
    {
        if (_logger.isLoggable(Level.WARNING))
            log(Level.WARNING,format(msg,args),null);
    }

    public void warn(Throwable thrown)
    {
        if (_logger.isLoggable(Level.WARNING))
            log(Level.WARNING,"",thrown);
    }

    public void warn(String msg, Throwable thrown)
    {
        if (_logger.isLoggable(Level.WARNING))
            log(Level.WARNING,msg,thrown);
    }

    public void info(String msg, Object... args)
    {
        if (_logger.isLoggable(Level.INFO))
            log(Level.INFO, format(msg, args),null);
    }

    public void info(Throwable thrown)
    {
        if (_logger.isLoggable(Level.INFO))
            log(Level.INFO, "",thrown);
    }

    public void info(String msg, Throwable thrown)
    {
        if (_logger.isLoggable(Level.INFO))
            log(Level.INFO,msg,thrown);
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
        if (_logger.isLoggable(Level.FINE))
            log(Level.FINE,format(msg, args),null);
    }

    public void debug(String msg, long arg)
    {
        if (_logger.isLoggable(Level.FINE))
            log(Level.FINE,format(msg, arg),null);
    }

    public void debug(Throwable thrown)
    {
        if (_logger.isLoggable(Level.FINE))
            log(Level.FINE,"",thrown);
    }

    public void debug(String msg, Throwable thrown)
    {
        if (_logger.isLoggable(Level.FINE))
            log(Level.FINE,msg,thrown);
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
        if (_logger.isLoggable(Level.WARNING))
            log(Level.WARNING,Log.IGNORED,ignored);
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
