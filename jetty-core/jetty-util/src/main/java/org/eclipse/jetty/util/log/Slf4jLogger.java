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

package org.eclipse.jetty.util.log;

import org.slf4j.LoggerFactory;

@Deprecated
class Slf4jLogger implements org.eclipse.jetty.util.log.Logger
{
    private final org.slf4j.Logger logger;

    Slf4jLogger(org.slf4j.Logger logger)
    {
        this.logger = logger;
    }

    @Override
    public void debug(String format, Object... args)
    {
        logger.debug(format, args);
    }

    @Override
    public void debug(String msg, long value)
    {
        logger.debug(msg, value);
    }

    @Override
    public void debug(Throwable cause)
    {
        logger.debug(cause.getMessage(), cause);
    }

    @Override
    public void debug(String msg, Throwable thrown)
    {
        logger.debug(msg, thrown);
    }

    @Override
    public org.eclipse.jetty.util.log.Logger getLogger(String name)
    {
        return new Slf4jLogger(LoggerFactory.getLogger(getName() + name));
    }

    @Override
    public void ignore(Throwable cause)
    {
        logger.trace("IGNORED", cause);
    }

    @Override
    public void info(String format, Object... args)
    {
        logger.info(format, args);
    }

    @Override
    public void info(Throwable cause)
    {
        logger.info(cause.getMessage(), cause);
    }

    @Override
    public void info(String msg, Throwable thrown)
    {
        logger.info(msg, thrown);
    }

    @Override
    public boolean isDebugEnabled()
    {
        return logger.isDebugEnabled();
    }

    @Override
    @Deprecated
    public void setDebugEnabled(boolean enabled)
    {
        // NOT SUPPORTED
    }

    @Override
    public void warn(Throwable cause)
    {
        logger.warn(cause.getMessage(), cause);
    }

    @Override
    public void warn(String msg, Throwable cause)
    {
        logger.warn(msg, cause);
    }

    @Override
    public String getName()
    {
        return logger.getName();
    }

    @Override
    public void warn(String format, Object... args)
    {
        logger.warn(format, args);
    }
}
