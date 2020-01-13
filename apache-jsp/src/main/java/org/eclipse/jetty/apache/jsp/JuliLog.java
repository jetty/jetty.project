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

package org.eclipse.jetty.apache.jsp;

public class JuliLog implements org.apache.juli.logging.Log
{
    public static org.apache.juli.logging.Log getInstance(String name)
    {
        return new JuliLog(name);
    }

    private final org.eclipse.jetty.util.log.Logger _logger;
    private final org.eclipse.jetty.util.log.StdErrLog _stdErrLog;

    public JuliLog()
    {
        _logger = org.eclipse.jetty.util.log.Log.getRootLogger();
        _stdErrLog = (_logger instanceof org.eclipse.jetty.util.log.StdErrLog) ? (org.eclipse.jetty.util.log.StdErrLog)_logger : null;
    }

    public JuliLog(String name)
    {
        _logger = org.eclipse.jetty.util.log.Log.getLogger(name);
        _stdErrLog = (_logger instanceof org.eclipse.jetty.util.log.StdErrLog) ? (org.eclipse.jetty.util.log.StdErrLog)_logger : null;
    }

    @Override
    public boolean isDebugEnabled()
    {
        return _logger.isDebugEnabled();
    }

    @Override
    public boolean isErrorEnabled()
    {
        return _stdErrLog == null ? true : _stdErrLog.getLevel() <= org.eclipse.jetty.util.log.StdErrLog.LEVEL_WARN;
    }

    @Override
    public boolean isFatalEnabled()
    {
        return _stdErrLog == null ? true : _stdErrLog.getLevel() <= org.eclipse.jetty.util.log.StdErrLog.LEVEL_WARN;
    }

    @Override
    public boolean isInfoEnabled()
    {
        return _stdErrLog == null ? true : _stdErrLog.getLevel() <= org.eclipse.jetty.util.log.StdErrLog.LEVEL_INFO;
    }

    @Override
    public boolean isTraceEnabled()
    {
        return _stdErrLog == null ? true : _stdErrLog.getLevel() <= org.eclipse.jetty.util.log.StdErrLog.LEVEL_DEBUG;
    }

    @Override
    public boolean isWarnEnabled()
    {
        return _stdErrLog == null ? true : _stdErrLog.getLevel() <= org.eclipse.jetty.util.log.StdErrLog.LEVEL_WARN;
    }

    @Override
    public void trace(Object message)
    {
        if (message instanceof String)
            _logger.debug((String)message);
        else
            _logger.debug("{}", message);
    }

    @Override
    public void trace(Object message, Throwable t)
    {
        if (message instanceof String)
            _logger.debug((String)message, t);
        else
            _logger.debug("{}", message, t);
    }

    @Override
    public void debug(Object message)
    {
        if (message instanceof String)
            _logger.debug((String)message);
        else
            _logger.debug("{}", message);
    }

    @Override
    public void debug(Object message, Throwable t)
    {
        if (message instanceof String)
            _logger.debug((String)message, t);
        else
            _logger.debug("{}", message, t);
    }

    @Override
    public void info(Object message)
    {
        if (message instanceof String)
            _logger.info((String)message);
        else
            _logger.info("{}", message);
    }

    @Override
    public void info(Object message, Throwable t)
    {
        if (message instanceof String)
            _logger.info((String)message, t);
        else
            _logger.info("{}", message, t);
    }

    @Override
    public void warn(Object message)
    {
        if (message instanceof String)
            _logger.warn((String)message);
        else
            _logger.warn("{}", message);
    }

    @Override
    public void warn(Object message, Throwable t)
    {
        if (message instanceof String)
            _logger.warn((String)message, t);
        else
            _logger.warn("{}", message, t);
    }

    @Override
    public void error(Object message)
    {
        if (message instanceof String)
            _logger.warn((String)message);
        else
            _logger.warn("{}", message);
    }

    @Override
    public void error(Object message, Throwable t)
    {
        if (message instanceof String)
            _logger.warn((String)message, t);
        else
            _logger.warn("{}", message, t);
    }

    @Override
    public void fatal(Object message)
    {
        if (message instanceof String)
            _logger.warn((String)message);
        else
            _logger.warn("{}", message);
    }

    @Override
    public void fatal(Object message, Throwable t)
    {
        if (message instanceof String)
            _logger.warn((String)message, t);
        else
            _logger.warn("{}", message, t);
    }
}


