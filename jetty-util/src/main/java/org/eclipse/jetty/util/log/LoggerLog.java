//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
            _debugMT = lc.getMethod("debug", String.class, Throwable.class);
            _debugMAA = lc.getMethod("debug", String.class, Object[].class);
            _infoMT = lc.getMethod("info", String.class, Throwable.class);
            _infoMAA = lc.getMethod("info", String.class, Object[].class);
            _warnMT = lc.getMethod("warn", String.class, Throwable.class);
            _warnMAA = lc.getMethod("warn", String.class, Object[].class);
            Method isDebugEnabled = lc.getMethod("isDebugEnabled");
            _setDebugEnabledE = lc.getMethod("setDebugEnabled", Boolean.TYPE);
            _getLoggerN = lc.getMethod("getLogger", String.class);
            _getName = lc.getMethod("getName");

            _debug = (Boolean)isDebugEnabled.invoke(_logger);
        }
        catch (Exception x)
        {
            throw new IllegalStateException(x);
        }
    }

    @Override
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

    @Override
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

    @Override
    public void warn(Throwable thrown)
    {
        warn("", thrown);
    }

    @Override
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

    @Override
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

    @Override
    public void info(Throwable thrown)
    {
        info("", thrown);
    }

    @Override
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

    @Override
    public boolean isDebugEnabled()
    {
        return _debug;
    }

    @Override
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

    @Override
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

    @Override
    public void debug(Throwable thrown)
    {
        debug("", thrown);
    }

    @Override
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

    @Override
    public void debug(String msg, long value)
    {
        if (!_debug)
            return;

        try
        {
            _debugMAA.invoke(_logger, new Long(value));
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    @Override
    public void ignore(Throwable ignored)
    {
        if (Log.isIgnored())
        {
            debug(Log.IGNORED, ignored);
        }
    }

    /**
     * Create a Child Logger of this Logger.
     */
    @Override
    protected Logger newLogger(String fullname)
    {
        try
        {
            Object logger = _getLoggerN.invoke(_logger, fullname);
            return new LoggerLog(logger);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return this;
        }
    }
}
