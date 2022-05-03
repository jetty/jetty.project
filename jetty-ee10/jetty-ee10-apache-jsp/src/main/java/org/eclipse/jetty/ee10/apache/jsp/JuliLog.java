//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.apache.jsp;

import org.slf4j.LoggerFactory;

public class JuliLog implements org.apache.juli.logging.Log
{
    public static org.apache.juli.logging.Log getInstance(String name)
    {
        return new JuliLog(name);
    }

    private final org.slf4j.Logger _logger;

    public JuliLog()
    {
        _logger = LoggerFactory.getLogger("");
    }

    public JuliLog(String name)
    {
        _logger = LoggerFactory.getLogger(name);
    }

    @Override
    public boolean isDebugEnabled()
    {
        return _logger.isDebugEnabled();
    }

    @Override
    public boolean isErrorEnabled()
    {
        return _logger.isErrorEnabled();
    }

    @Override
    public boolean isFatalEnabled()
    {
        return _logger.isErrorEnabled();
    }

    @Override
    public boolean isInfoEnabled()
    {
        return _logger.isInfoEnabled();
    }

    @Override
    public boolean isTraceEnabled()
    {
        return _logger.isTraceEnabled();
    }

    @Override
    public boolean isWarnEnabled()
    {
        return _logger.isWarnEnabled();
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


