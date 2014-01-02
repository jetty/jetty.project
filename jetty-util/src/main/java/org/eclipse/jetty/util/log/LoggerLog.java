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

import java.lang.reflect.Method;

/**
 *
 */
public class LoggerLog extends AbstractLogger
{
    private final Object _logger;
    private final Method _debugMT;
    private final Method _debugMAA;
    private final Method _infoMT;
    private final Method _infoMAA;
    private final Method _warnMT;
    private final Method _warnMAA;
    private final Method _setDebugEnabledE;
    private final Method _getLoggerN;
    private final Method _getName;
    private volatile boolean _debug;

    public LoggerLog(Object logger)
    {
        try
        {
            _logger = logger;
            Class<?> lc = logger.getClass();
            _debugMT = lc.getMethod("debug", new Class[]{String.class, Throwable.class});
            _debugMAA = lc.getMethod("debug", new Class[]{String.class, Object[].class});
            _infoMT = lc.getMethod("info", new Class[]{String.class, Throwable.class});
            _infoMAA = lc.getMethod("info", new Class[]{String.class, Object[].class});
            _warnMT = lc.getMethod("warn", new Class[]{String.class, Throwable.class});
            _warnMAA = lc.getMethod("warn", new Class[]{String.class, Object[].class});
            Method _isDebugEnabled = lc.getMethod("isDebugEnabled");
            _setDebugEnabledE = lc.getMethod("setDebugEnabled", new Class[]{Boolean.TYPE});
            _getLoggerN = lc.getMethod("getLogger", new Class[]{String.class});
            _getName = lc.getMethod("getName");

            _debug = (Boolean)_isDebugEnabled.invoke(_logger);
        }
        catch(Exception x)
        {
            throw new IllegalStateException(x);
        }
    }

    public String getName()
    {
        try
        {
            return (String)_getName.invoke(_logger);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return null;
        }
    }

    public void warn(String msg, Object... args)
    {
        try
        {
            _warnMAA.invoke(_logger, args);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    public void warn(Throwable thrown)
    {
        warn("", thrown);
    }

    public void warn(String msg, Throwable thrown)
    {
        try
        {
            _warnMT.invoke(_logger, msg, thrown);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    public void info(String msg, Object... args)
    {
        try
        {
            _infoMAA.invoke(_logger, args);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    public void info(Throwable thrown)
    {
        info("", thrown);
    }

    public void info(String msg, Throwable thrown)
    {
        try
        {
            _infoMT.invoke(_logger, msg, thrown);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    public boolean isDebugEnabled()
    {
        return _debug;
    }

    public void setDebugEnabled(boolean enabled)
    {
        try
        {
            _setDebugEnabledE.invoke(_logger, enabled);
            _debug = enabled;
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    public void debug(String msg, Object... args)
    {
        if (!_debug)
            return;

        try
        {
            _debugMAA.invoke(_logger, args);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    public void debug(Throwable thrown)
    {
        debug("", thrown);
    }

    public void debug(String msg, Throwable th)
    {
        if (!_debug)
            return;

        try
        {
            _debugMT.invoke(_logger, msg, th);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    public void ignore(Throwable ignored)
    {
        if (Log.isIgnored())
        {
            warn(Log.IGNORED, ignored);
        }
    }

    /**
     * Create a Child Logger of this Logger.
     */
    protected Logger newLogger(String fullname)
    {
        try
        {
            Object logger=_getLoggerN.invoke(_logger, fullname);
            return new LoggerLog(logger);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return this;
        }
    }
}
