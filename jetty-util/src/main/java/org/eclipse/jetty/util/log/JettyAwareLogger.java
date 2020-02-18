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

package org.eclipse.jetty.util.log;

import org.slf4j.Marker;
import org.slf4j.helpers.FormattingTuple;
import org.slf4j.helpers.MessageFormatter;

/**
 * JettyAwareLogger is used to fix a FQCN bug that arises from how Jetty
 * Log uses an indirect slf4j implementation.
 *
 * https://bugs.eclipse.org/bugs/show_bug.cgi?id=276670
 */
class JettyAwareLogger implements org.slf4j.Logger
{
    private static final int DEBUG = org.slf4j.spi.LocationAwareLogger.DEBUG_INT;
    private static final int ERROR = org.slf4j.spi.LocationAwareLogger.ERROR_INT;
    private static final int INFO = org.slf4j.spi.LocationAwareLogger.INFO_INT;
    private static final int TRACE = org.slf4j.spi.LocationAwareLogger.TRACE_INT;
    private static final int WARN = org.slf4j.spi.LocationAwareLogger.WARN_INT;

    private static final String FQCN = Slf4jLog.class.getName();
    private final org.slf4j.spi.LocationAwareLogger _logger;

    public JettyAwareLogger(org.slf4j.spi.LocationAwareLogger logger)
    {
        _logger = logger;
    }

    @Override
    public void debug(String msg)
    {
        log(null, DEBUG, msg, null, null);
    }

    @Override
    public void debug(String format, Object arg)
    {
        log(null, DEBUG, format, new Object[]{arg}, null);
    }

    @Override
    public void debug(String format, Object arg1, Object arg2)
    {
        log(null, DEBUG, format, new Object[]{arg1, arg2}, null);
    }

    @Override
    public void debug(String format, Object[] argArray)
    {
        log(null, DEBUG, format, argArray, null);
    }

    @Override
    public void debug(String msg, Throwable t)
    {
        log(null, DEBUG, msg, null, t);
    }

    @Override
    public void debug(Marker marker, String msg)
    {
        log(marker, DEBUG, msg, null, null);
    }

    @Override
    public void debug(Marker marker, String format, Object arg)
    {
        log(marker, DEBUG, format, new Object[]{arg}, null);
    }

    @Override
    public void debug(Marker marker, String format, Object arg1, Object arg2)
    {
        log(marker, DEBUG, format, new Object[]{arg1, arg2}, null);
    }

    @Override
    public void debug(Marker marker, String format, Object[] argArray)
    {
        log(marker, DEBUG, format, argArray, null);
    }

    @Override
    public void debug(Marker marker, String msg, Throwable t)
    {
        log(marker, DEBUG, msg, null, t);
    }

    @Override
    public void error(String msg)
    {
        log(null, ERROR, msg, null, null);
    }

    @Override
    public void error(String format, Object arg)
    {
        log(null, ERROR, format, new Object[]{arg}, null);
    }

    @Override
    public void error(String format, Object arg1, Object arg2)
    {
        log(null, ERROR, format, new Object[]{arg1, arg2}, null);
    }

    @Override
    public void error(String format, Object[] argArray)
    {
        log(null, ERROR, format, argArray, null);
    }

    @Override
    public void error(String msg, Throwable t)
    {
        log(null, ERROR, msg, null, t);
    }

    @Override
    public void error(Marker marker, String msg)
    {
        log(marker, ERROR, msg, null, null);
    }

    @Override
    public void error(Marker marker, String format, Object arg)
    {
        log(marker, ERROR, format, new Object[]{arg}, null);
    }

    @Override
    public void error(Marker marker, String format, Object arg1, Object arg2)
    {
        log(marker, ERROR, format, new Object[]{arg1, arg2}, null);
    }

    @Override
    public void error(Marker marker, String format, Object[] argArray)
    {
        log(marker, ERROR, format, argArray, null);
    }

    @Override
    public void error(Marker marker, String msg, Throwable t)
    {
        log(marker, ERROR, msg, null, t);
    }

    @Override
    public String getName()
    {
        return _logger.getName();
    }

    @Override
    public void info(String msg)
    {
        log(null, INFO, msg, null, null);
    }

    @Override
    public void info(String format, Object arg)
    {
        log(null, INFO, format, new Object[]{arg}, null);
    }

    @Override
    public void info(String format, Object arg1, Object arg2)
    {
        log(null, INFO, format, new Object[]{arg1, arg2}, null);
    }

    @Override
    public void info(String format, Object[] argArray)
    {
        log(null, INFO, format, argArray, null);
    }

    @Override
    public void info(String msg, Throwable t)
    {
        log(null, INFO, msg, null, t);
    }

    @Override
    public void info(Marker marker, String msg)
    {
        log(marker, INFO, msg, null, null);
    }

    @Override
    public void info(Marker marker, String format, Object arg)
    {
        log(marker, INFO, format, new Object[]{arg}, null);
    }

    @Override
    public void info(Marker marker, String format, Object arg1, Object arg2)
    {
        log(marker, INFO, format, new Object[]{arg1, arg2}, null);
    }

    @Override
    public void info(Marker marker, String format, Object[] argArray)
    {
        log(marker, INFO, format, argArray, null);
    }

    @Override
    public void info(Marker marker, String msg, Throwable t)
    {
        log(marker, INFO, msg, null, t);
    }

    @Override
    public boolean isDebugEnabled()
    {
        return _logger.isDebugEnabled();
    }

    @Override
    public boolean isDebugEnabled(Marker marker)
    {
        return _logger.isDebugEnabled(marker);
    }

    @Override
    public boolean isErrorEnabled()
    {
        return _logger.isErrorEnabled();
    }

    @Override
    public boolean isErrorEnabled(Marker marker)
    {
        return _logger.isErrorEnabled(marker);
    }

    @Override
    public boolean isInfoEnabled()
    {
        return _logger.isInfoEnabled();
    }

    @Override
    public boolean isInfoEnabled(Marker marker)
    {
        return _logger.isInfoEnabled(marker);
    }

    @Override
    public boolean isTraceEnabled()
    {
        return _logger.isTraceEnabled();
    }

    @Override
    public boolean isTraceEnabled(Marker marker)
    {
        return _logger.isTraceEnabled(marker);
    }

    @Override
    public boolean isWarnEnabled()
    {
        return _logger.isWarnEnabled();
    }

    @Override
    public boolean isWarnEnabled(Marker marker)
    {
        return _logger.isWarnEnabled(marker);
    }

    @Override
    public String toString()
    {
        return _logger.toString();
    }

    @Override
    public void trace(String msg)
    {
        log(null, TRACE, msg, null, null);
    }

    @Override
    public void trace(String format, Object arg)
    {
        log(null, TRACE, format, new Object[]{arg}, null);
    }

    @Override
    public void trace(String format, Object arg1, Object arg2)
    {
        log(null, TRACE, format, new Object[]{arg1, arg2}, null);
    }

    @Override
    public void trace(String format, Object[] argArray)
    {
        log(null, TRACE, format, argArray, null);
    }

    @Override
    public void trace(String msg, Throwable t)
    {
        log(null, TRACE, msg, null, t);
    }

    @Override
    public void trace(Marker marker, String msg)
    {
        log(marker, TRACE, msg, null, null);
    }

    @Override
    public void trace(Marker marker, String format, Object arg)
    {
        log(marker, TRACE, format, new Object[]{arg}, null);
    }

    @Override
    public void trace(Marker marker, String format, Object arg1, Object arg2)
    {
        log(marker, TRACE, format, new Object[]{arg1, arg2}, null);
    }

    @Override
    public void trace(Marker marker, String format, Object[] argArray)
    {
        log(marker, TRACE, format, argArray, null);
    }

    @Override
    public void trace(Marker marker, String msg, Throwable t)
    {
        log(marker, TRACE, msg, null, t);
    }

    @Override
    public void warn(String msg)
    {
        log(null, WARN, msg, null, null);
    }

    @Override
    public void warn(String format, Object arg)
    {
        log(null, WARN, format, new Object[]{arg}, null);
    }

    @Override
    public void warn(String format, Object[] argArray)
    {
        log(null, WARN, format, argArray, null);
    }

    @Override
    public void warn(String format, Object arg1, Object arg2)
    {
        log(null, WARN, format, new Object[]{arg1, arg2}, null);
    }

    @Override
    public void warn(String msg, Throwable t)
    {
        log(null, WARN, msg, null, t);
    }

    @Override
    public void warn(Marker marker, String msg)
    {
        log(marker, WARN, msg, null, null);
    }

    @Override
    public void warn(Marker marker, String format, Object arg)
    {
        log(marker, WARN, format, new Object[]{arg}, null);
    }

    @Override
    public void warn(Marker marker, String format, Object arg1, Object arg2)
    {
        log(marker, WARN, format, new Object[]{arg1, arg2}, null);
    }

    @Override
    public void warn(Marker marker, String format, Object[] argArray)
    {
        log(marker, WARN, format, argArray, null);
    }

    @Override
    public void warn(Marker marker, String msg, Throwable t)
    {
        log(marker, WARN, msg, null, t);
    }

    private void log(Marker marker, int level, String msg, Object[] argArray, Throwable t)
    {
        if (argArray == null)
        {
            // Simple SLF4J Message (no args)
            _logger.log(marker, FQCN, level, msg, null, t);
        }
        else
        {
            int loggerLevel = _logger.isTraceEnabled()
                ? TRACE
                : _logger.isDebugEnabled()
                ? DEBUG
                : _logger.isInfoEnabled()
                ? INFO
                : _logger.isWarnEnabled()
                ? WARN
                : ERROR;
            if (loggerLevel <= level)
            {
                // Don't assume downstream handles argArray properly.
                // Do it the SLF4J way here to eliminate that as a bug.
                FormattingTuple ft = MessageFormatter.arrayFormat(msg, argArray);
                _logger.log(marker, FQCN, level, ft.getMessage(), null, t);
            }
        }
    }
}
