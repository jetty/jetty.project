// ========================================================================
// Copyright (c) Webtide LLC
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
//
// The Apache License v2.0 is available at
// http://www.apache.org/licenses/LICENSE-2.0.txt
//
// You may elect to redistribute this code under either of these licenses.
// ========================================================================
package org.eclipse.jetty.logging.impl;

import java.io.IOException;

import org.eclipse.jetty.util.DateCache;
import org.slf4j.helpers.MarkerIgnoringBase;
import org.slf4j.helpers.MessageFormatter;

/**
 * Centralized Logger implementation.
 */
public class CentralLogger extends MarkerIgnoringBase
{
    private static final long serialVersionUID = 385001265755850685L;
    private static DateCache dateCache;
    private Severity level = Severity.INFO;
    private String name;
    private Appender appenders[];

    static
    {
        try
        {
            dateCache = new DateCache("yyyy-MM-dd HH:mm:ss");
        }
        catch (Exception e)
        {
            e.printStackTrace(System.err);
        }
    }

    protected CentralLogger(String name, Appender appenders[], Severity severity)
    {
        this.name = name;
        this.appenders = appenders;
        this.level = severity;
    }

    private void log(Severity severity, String message, Throwable t)
    {
        String now = dateCache.now();
        int ms = dateCache.lastMs();

        for (Appender appender : appenders)
        {
            try
            {
                appender.append(now,ms,severity,name,message,t);
            }
            catch (IOException e)
            {
                e.printStackTrace();
            }
        }
    }

    private void logFormatted(Severity severity, String format, Object arg)
    {
        String msg = MessageFormatter.format(format,arg);
        log(severity,msg,null);
    }

    private void logFormatted(Severity severity, String format, Object arg1, Object arg2)
    {
        String msg = MessageFormatter.format(format,arg1,arg2);
        log(severity,msg,null);
    }

    private void logFormatted(Severity severity, String format, Object[] argArray)
    {
        String msg = MessageFormatter.arrayFormat(format,argArray);
        log(severity,msg,null);
    }

    public void debug(String msg)
    {
        log(Severity.DEBUG,msg,null);
    }

    public void debug(String format, Object arg)
    {
        logFormatted(Severity.DEBUG,format,arg);
    }

    public void debug(String format, Object arg1, Object arg2)
    {
        logFormatted(Severity.DEBUG,format,arg1,arg2);
    }

    public void debug(String format, Object[] argArray)
    {
        logFormatted(Severity.DEBUG,format,argArray);
    }

    public void debug(String msg, Throwable t)
    {
        log(Severity.DEBUG,msg,t);
    }

    public void error(String msg)
    {
        log(Severity.ERROR,msg,null);
    }

    public void error(String format, Object arg)
    {
        logFormatted(Severity.ERROR,format,arg);
    }

    public void error(String format, Object arg1, Object arg2)
    {
        logFormatted(Severity.ERROR,format,arg1,arg2);
    }

    public void error(String format, Object[] argArray)
    {
        logFormatted(Severity.ERROR,format,argArray);
    }

    public void error(String msg, Throwable t)
    {
        log(Severity.ERROR,msg,t);
    }

    public void info(String msg)
    {
        log(Severity.INFO,msg,null);
    }

    public void info(String format, Object arg)
    {
        logFormatted(Severity.INFO,format,arg);
    }

    public void info(String format, Object arg1, Object arg2)
    {
        logFormatted(Severity.INFO,format,arg1,arg2);
    }

    public void info(String format, Object[] argArray)
    {
        logFormatted(Severity.INFO,format,argArray);
    }

    public void info(String msg, Throwable t)
    {
        log(Severity.INFO,msg,t);
    }

    public boolean isDebugEnabled()
    {
        return level.isEnabled(Severity.DEBUG);
    }

    public boolean isErrorEnabled()
    {
        return level.isEnabled(Severity.ERROR);
    }

    public boolean isInfoEnabled()
    {
        return level.isEnabled(Severity.INFO);
    }

    public boolean isTraceEnabled()
    {
        return level.isEnabled(Severity.TRACE);
    }

    public boolean isWarnEnabled()
    {
        return level.isEnabled(Severity.WARN);
    }

    public void trace(String msg)
    {
        log(Severity.TRACE,msg,null);
    }

    public void trace(String format, Object arg)
    {
        logFormatted(Severity.TRACE,format,arg);
    }

    public void trace(String format, Object arg1, Object arg2)
    {
        logFormatted(Severity.TRACE,format,arg1,arg2);
    }

    public void trace(String format, Object[] argArray)
    {
        logFormatted(Severity.TRACE,format,argArray);
    }

    public void trace(String msg, Throwable t)
    {
        log(Severity.TRACE,msg,t);
    }

    public void warn(String msg)
    {
        log(Severity.WARN,msg,null);
    }

    public void warn(String format, Object arg)
    {
        logFormatted(Severity.WARN,format,arg);
    }

    public void warn(String format, Object arg1, Object arg2)
    {
        logFormatted(Severity.WARN,format,arg1,arg2);
    }

    public void warn(String format, Object[] argArray)
    {
        logFormatted(Severity.WARN,format,argArray);
    }

    public void warn(String msg, Throwable t)
    {
        log(Severity.WARN,msg,t);
    }
}
