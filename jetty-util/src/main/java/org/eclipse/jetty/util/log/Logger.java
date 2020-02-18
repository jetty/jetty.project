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

/**
 * Legacy Bridge API to Slf4j
 *
 * @deprecated
 */
@Deprecated
public class Logger
{
    private final org.slf4j.Logger logger;

    public Logger(org.slf4j.Logger logger)
    {
        this.logger = logger;
    }

    public void debug(String msg)
    {
        logger.debug(msg);
    }

    public void debug(String format, Object... args)
    {
        logger.debug(format, args);
    }

    public void debug(Throwable cause)
    {
        logger.debug(cause.getMessage(), cause);
    }

    public void ignore(Throwable cause)
    {
        logger.trace("IGNORED", cause);
    }

    public void info(String msg)
    {
        logger.info(msg);
    }

    public void info(String format, Object... args)
    {
        logger.info(format, args);
    }

    public void info(Throwable cause)
    {
        logger.info(cause.getMessage(), cause);
    }

    public boolean isDebugEnabled()
    {
        return logger.isDebugEnabled();
    }

    public void warn(String msg)
    {
        logger.warn(msg);
    }

    public void warn(Throwable cause)
    {
        logger.warn(cause.getMessage(), cause);
    }

    public void warn(String msg, Throwable cause)
    {
        logger.warn(msg, cause);
    }

    public void warn(String format, Object... args)
    {
        logger.warn(format, args);
    }
}
