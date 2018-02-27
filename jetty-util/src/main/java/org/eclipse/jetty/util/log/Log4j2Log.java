//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

import org.apache.logging.log4j.LogManager;

/**
 * Implementation of {@link Logger} based on {@link org.apache.logging.log4j.Logger}.
 */
public class Log4j2Log extends AbstractLogger
{
    private volatile boolean debugEnabled = false;

    private final org.apache.logging.log4j.Logger log;

    public Log4j2Log(String fullname)
    {
        log = LogManager.getLogger(fullname);
    }

    @Override
    protected Logger newLogger(String fullname)
    {
        return new Log4j2Log(fullname);
    }

    @Override
    public String getName()
    {
        return log.getName();
    }

    @Override
    public void warn(String msg, Object... args)
    {
        log.warn(msg, args);
    }

    @Override
    public void warn(Throwable thrown)
    {
        log.warn(thrown);
    }

    @Override
    public void warn(String msg, Throwable thrown)
    {
        log.warn(msg, thrown);
    }

    @Override
    public void info(String msg, Object... args)
    {
        log.info(msg, args);
    }

    @Override
    public void info(Throwable thrown)
    {
        log.info(thrown);
    }

    @Override
    public void info(String msg, Throwable thrown)
    {
        log.info(msg);
    }

    @Override
    public boolean isDebugEnabled()
    {
        return log.isDebugEnabled() || debugEnabled;
    }

    @Override
    public void setDebugEnabled(boolean enabled)
    {
        debugEnabled = enabled;
    }

    @Override
    public void debug(String msg, Object... args)
    {
        if (isDebugEnabled())
            log.debug(msg, args);
    }

    @Override
    public void debug(Throwable thrown)
    {
        if (isDebugEnabled())
            log.debug(thrown);
    }

    @Override
    public void debug(String msg, Throwable thrown)
    {
        if (isDebugEnabled())
            log.debug(msg, thrown);
    }

    @Override
    public void ignore(Throwable ignored)
    {
        if (isDebugEnabled())
            log.debug("Ignoring: " + ignored.getMessage());
    }
}
