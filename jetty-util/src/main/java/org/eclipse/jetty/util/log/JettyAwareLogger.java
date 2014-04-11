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
    private static final int INFO  = org.slf4j.spi.LocationAwareLogger.INFO_INT;
    private static final int TRACE = org.slf4j.spi.LocationAwareLogger.TRACE_INT;
    private static final int WARN  = org.slf4j.spi.LocationAwareLogger.WARN_INT;

    private static final String FQCN = Slf4jLog.class.getName();
    private final org.slf4j.spi.LocationAwareLogger _logger;

    public JettyAwareLogger(org.slf4j.spi.LocationAwareLogger logger)
    {
        _logger = logger;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#getName()
     */
    public String getName()
    {
        return _logger.getName();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#isTraceEnabled()
     */
    public boolean isTraceEnabled()
    {
        return _logger.isTraceEnabled();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#trace(java.lang.String)
     */
    public void trace(String msg)
    {
        log(null, TRACE, msg, null, null);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#trace(java.lang.String, java.lang.Object)
     */
    public void trace(String format, Object arg)
    {
        log(null, TRACE, format, new Object[]{arg}, null);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#trace(java.lang.String, java.lang.Object, java.lang.Object)
     */
    public void trace(String format, Object arg1, Object arg2)
    {
        log(null, TRACE, format, new Object[]{arg1,arg2}, null);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#trace(java.lang.String, java.lang.Object[])
     */
    public void trace(String format, Object[] argArray)
    {
        log(null, TRACE, format, argArray, null);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#trace(java.lang.String, java.lang.Throwable)
     */
    public void trace(String msg, Throwable t)
    {
        log(null, TRACE, msg, null, t);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#isTraceEnabled(org.slf4j.Marker)
     */
    public boolean isTraceEnabled(Marker marker)
    {
        return _logger.isTraceEnabled(marker);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#trace(org.slf4j.Marker, java.lang.String)
     */
    public void trace(Marker marker, String msg)
    {
        log(marker, TRACE, msg, null, null);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#trace(org.slf4j.Marker, java.lang.String, java.lang.Object)
     */
    public void trace(Marker marker, String format, Object arg)
    {
        log(marker, TRACE, format, new Object[]{arg}, null);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#trace(org.slf4j.Marker, java.lang.String, java.lang.Object, java.lang.Object)
     */
    public void trace(Marker marker, String format, Object arg1, Object arg2)
    {
        log(marker, TRACE, format, new Object[]{arg1,arg2}, null);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#trace(org.slf4j.Marker, java.lang.String, java.lang.Object[])
     */
    public void trace(Marker marker, String format, Object[] argArray)
    {
        log(marker, TRACE, format, argArray, null);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#trace(org.slf4j.Marker, java.lang.String, java.lang.Throwable)
     */
    public void trace(Marker marker, String msg, Throwable t)
    {
        log(marker, TRACE, msg, null, t);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#isDebugEnabled()
     */
    public boolean isDebugEnabled()
    {
        return _logger.isDebugEnabled();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#debug(java.lang.String)
     */
    public void debug(String msg)
    {
        log(null, DEBUG, msg, null, null);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#debug(java.lang.String, java.lang.Object)
     */
    public void debug(String format, Object arg)
    {
        log(null, DEBUG, format, new Object[]{arg}, null);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#debug(java.lang.String, java.lang.Object, java.lang.Object)
     */
    public void debug(String format, Object arg1, Object arg2)
    {
        log(null, DEBUG, format, new Object[]{arg1,arg2}, null);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#debug(java.lang.String, java.lang.Object[])
     */
    public void debug(String format, Object[] argArray)
    {
        log(null, DEBUG, format, argArray, null);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#debug(java.lang.String, java.lang.Throwable)
     */
    public void debug(String msg, Throwable t)
    {
        log(null, DEBUG, msg, null, t);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#isDebugEnabled(org.slf4j.Marker)
     */
    public boolean isDebugEnabled(Marker marker)
    {
        return _logger.isDebugEnabled(marker);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#debug(org.slf4j.Marker, java.lang.String)
     */
    public void debug(Marker marker, String msg)
    {
        log(marker, DEBUG, msg, null, null);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#debug(org.slf4j.Marker, java.lang.String, java.lang.Object)
     */
    public void debug(Marker marker, String format, Object arg)
    {
        log(marker, DEBUG, format, new Object[]{arg}, null);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#debug(org.slf4j.Marker, java.lang.String, java.lang.Object, java.lang.Object)
     */
    public void debug(Marker marker, String format, Object arg1, Object arg2)
    {
        log(marker, DEBUG, format, new Object[]{arg1,arg2}, null);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#debug(org.slf4j.Marker, java.lang.String, java.lang.Object[])
     */
    public void debug(Marker marker, String format, Object[] argArray)
    {
        log(marker, DEBUG, format, argArray, null);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#debug(org.slf4j.Marker, java.lang.String, java.lang.Throwable)
     */
    public void debug(Marker marker, String msg, Throwable t)
    {
        log(marker, DEBUG, msg, null, t);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#isInfoEnabled()
     */
    public boolean isInfoEnabled()
    {
        return _logger.isInfoEnabled();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#info(java.lang.String)
     */
    public void info(String msg)
    {
        log(null, INFO, msg, null, null);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#info(java.lang.String, java.lang.Object)
     */
    public void info(String format, Object arg)
    {
        log(null, INFO, format, new Object[]{arg}, null);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#info(java.lang.String, java.lang.Object, java.lang.Object)
     */
    public void info(String format, Object arg1, Object arg2)
    {
        log(null, INFO, format, new Object[]{arg1,arg2}, null);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#info(java.lang.String, java.lang.Object[])
     */
    public void info(String format, Object[] argArray)
    {
        log(null, INFO, format, argArray, null);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#info(java.lang.String, java.lang.Throwable)
     */
    public void info(String msg, Throwable t)
    {
        log(null, INFO, msg, null, t);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#isInfoEnabled(org.slf4j.Marker)
     */
    public boolean isInfoEnabled(Marker marker)
    {
        return _logger.isInfoEnabled(marker);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#info(org.slf4j.Marker, java.lang.String)
     */
    public void info(Marker marker, String msg)
    {
        log(marker, INFO, msg, null, null);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#info(org.slf4j.Marker, java.lang.String, java.lang.Object)
     */
    public void info(Marker marker, String format, Object arg)
    {
        log(marker, INFO, format, new Object[]{arg}, null);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#info(org.slf4j.Marker, java.lang.String, java.lang.Object, java.lang.Object)
     */
    public void info(Marker marker, String format, Object arg1, Object arg2)
    {
        log(marker, INFO, format, new Object[]{arg1,arg2}, null);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#info(org.slf4j.Marker, java.lang.String, java.lang.Object[])
     */
    public void info(Marker marker, String format, Object[] argArray)
    {
        log(marker, INFO, format, argArray, null);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#info(org.slf4j.Marker, java.lang.String, java.lang.Throwable)
     */
    public void info(Marker marker, String msg, Throwable t)
    {
        log(marker, INFO, msg, null, t);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#isWarnEnabled()
     */
    public boolean isWarnEnabled()
    {
        return _logger.isWarnEnabled();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#warn(java.lang.String)
     */
    public void warn(String msg)
    {
        log(null, WARN, msg, null, null);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#warn(java.lang.String, java.lang.Object)
     */
    public void warn(String format, Object arg)
    {
        log(null, WARN, format, new Object[]{arg}, null);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#warn(java.lang.String, java.lang.Object[])
     */
    public void warn(String format, Object[] argArray)
    {
        log(null, WARN, format, argArray, null);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#warn(java.lang.String, java.lang.Object, java.lang.Object)
     */
    public void warn(String format, Object arg1, Object arg2)
    {
        log(null, WARN, format, new Object[]{arg1,arg2}, null);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#warn(java.lang.String, java.lang.Throwable)
     */
    public void warn(String msg, Throwable t)
    {
        log(null, WARN, msg, null, t);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#isWarnEnabled(org.slf4j.Marker)
     */
    public boolean isWarnEnabled(Marker marker)
    {
        return _logger.isWarnEnabled(marker);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#warn(org.slf4j.Marker, java.lang.String)
     */
    public void warn(Marker marker, String msg)
    {
        log(marker, WARN, msg, null, null);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#warn(org.slf4j.Marker, java.lang.String, java.lang.Object)
     */
    public void warn(Marker marker, String format, Object arg)
    {
        log(marker, WARN, format, new Object[]{arg}, null);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#warn(org.slf4j.Marker, java.lang.String, java.lang.Object, java.lang.Object)
     */
    public void warn(Marker marker, String format, Object arg1, Object arg2)
    {
        log(marker, WARN, format, new Object[]{arg1,arg2}, null);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#warn(org.slf4j.Marker, java.lang.String, java.lang.Object[])
     */
    public void warn(Marker marker, String format, Object[] argArray)
    {
        log(marker, WARN, format, argArray, null);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#warn(org.slf4j.Marker, java.lang.String, java.lang.Throwable)
     */
    public void warn(Marker marker, String msg, Throwable t)
    {
        log(marker, WARN, msg, null, t);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#isErrorEnabled()
     */
    public boolean isErrorEnabled()
    {
        return _logger.isErrorEnabled();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#error(java.lang.String)
     */
    public void error(String msg)
    {
        log(null, ERROR, msg, null, null);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#error(java.lang.String, java.lang.Object)
     */
    public void error(String format, Object arg)
    {
        log(null, ERROR, format, new Object[]{arg}, null);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#error(java.lang.String, java.lang.Object, java.lang.Object)
     */
    public void error(String format, Object arg1, Object arg2)
    {
        log(null, ERROR, format, new Object[]{arg1,arg2}, null);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#error(java.lang.String, java.lang.Object[])
     */
    public void error(String format, Object[] argArray)
    {
        log(null, ERROR, format, argArray, null);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#error(java.lang.String, java.lang.Throwable)
     */
    public void error(String msg, Throwable t)
    {
        log(null, ERROR, msg, null, t);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#isErrorEnabled(org.slf4j.Marker)
     */
    public boolean isErrorEnabled(Marker marker)
    {
        return _logger.isErrorEnabled(marker);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#error(org.slf4j.Marker, java.lang.String)
     */
    public void error(Marker marker, String msg)
    {
        log(marker, ERROR, msg, null, null);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#error(org.slf4j.Marker, java.lang.String, java.lang.Object)
     */
    public void error(Marker marker, String format, Object arg)
    {
        log(marker, ERROR, format, new Object[]{arg}, null);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#error(org.slf4j.Marker, java.lang.String, java.lang.Object, java.lang.Object)
     */
    public void error(Marker marker, String format, Object arg1, Object arg2)
    {
        log(marker, ERROR, format, new Object[]{arg1,arg2}, null);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#error(org.slf4j.Marker, java.lang.String, java.lang.Object[])
     */
    public void error(Marker marker, String format, Object[] argArray)
    {
        log(marker, ERROR, format, argArray, null);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.slf4j.Logger#error(org.slf4j.Marker, java.lang.String, java.lang.Throwable)
     */
    public void error(Marker marker, String msg, Throwable t)
    {
        log(marker, ERROR, msg, null, t);
    }

    @Override
    public String toString()
    {
        return _logger.toString();
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
            int loggerLevel = _logger.isTraceEnabled() ? TRACE :
                    _logger.isDebugEnabled() ? DEBUG :
                            _logger.isInfoEnabled() ? INFO :
                                    _logger.isWarnEnabled() ? WARN : ERROR;
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
