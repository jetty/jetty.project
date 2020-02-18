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

import java.util.Objects;

import org.slf4j.LoggerFactory;

public class JuliLog implements org.apache.juli.logging.Log
{
    public static org.apache.juli.logging.Log getInstance(String name)
    {
        return new JuliLog(name);
    }

    private final org.slf4j.Logger logger;

    public JuliLog()
    {
        this(JuliLog.class.getName());
    }

    public JuliLog(String name)
    {
        logger = LoggerFactory.getLogger(name);
    }

    @Override
    public boolean isDebugEnabled()
    {
        return logger.isDebugEnabled();
    }

    @Override
    public boolean isErrorEnabled()
    {
        return logger.isErrorEnabled();
    }

    @Override
    public boolean isFatalEnabled()
    {
        return logger.isErrorEnabled();
    }

    @Override
    public boolean isInfoEnabled()
    {
        return logger.isInfoEnabled();
    }

    @Override
    public boolean isTraceEnabled()
    {
        return logger.isTraceEnabled();
    }

    @Override
    public boolean isWarnEnabled()
    {
        return logger.isWarnEnabled();
    }

    @Override
    public void trace(Object message)
    {
        logger.trace(Objects.toString(message));
    }

    @Override
    public void trace(Object message, Throwable t)
    {
        logger.trace(Objects.toString(message), t);
    }

    @Override
    public void debug(Object message)
    {
        logger.debug(Objects.toString(message));
    }

    @Override
    public void debug(Object message, Throwable t)
    {
        logger.debug(Objects.toString(message), t);
    }

    @Override
    public void info(Object message)
    {
        logger.info(Objects.toString(message));
    }

    @Override
    public void info(Object message, Throwable t)
    {
        logger.info(Objects.toString(message), t);
    }

    @Override
    public void warn(Object message)
    {
        logger.warn(Objects.toString(message));
    }

    @Override
    public void warn(Object message, Throwable t)
    {
        logger.warn(Objects.toString(message), t);
    }

    @Override
    public void error(Object message)
    {
        logger.error(Objects.toString(message));
    }

    @Override
    public void error(Object message, Throwable t)
    {
        logger.error(Objects.toString(message), t);
    }

    @Override
    public void fatal(Object message)
    {
        logger.error(Objects.toString(message));
    }

    @Override
    public void fatal(Object message, Throwable t)
    {
        logger.error(Objects.toString(message), t);
    }
}


