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

/**
 * Slf4jLog Logger
 */
public class Slf4jLog extends AbstractLogger
{
    private final org.slf4j.Logger _logger;

    public Slf4jLog() throws Exception
    {
        this("org.eclipse.jetty.util.log");
    }

    public Slf4jLog(String name)
    {
        //NOTE: if only an slf4j-api jar is on the classpath, slf4j will use a NOPLogger
        org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(name);

        // Fix LocationAwareLogger use to indicate FQCN of this class - 
        // https://bugs.eclipse.org/bugs/show_bug.cgi?id=276670
        if (logger instanceof org.slf4j.spi.LocationAwareLogger)
        {
            _logger = new JettyAwareLogger((org.slf4j.spi.LocationAwareLogger)logger);
        }
        else
        {
            _logger = logger;
        }
    }

    @Override
    public String getName()
    {
        return _logger.getName();
    }

    @Override
    public void warn(String msg, Object... args)
    {
        _logger.warn(msg, args);
    }

    @Override
    public void warn(Throwable thrown)
    {
        warn("", thrown);
    }

    @Override
    public void warn(String msg, Throwable thrown)
    {
        _logger.warn(msg, thrown);
    }

    @Override
    public void info(String msg, Object... args)
    {
        _logger.info(msg, args);
    }

    @Override
    public void info(Throwable thrown)
    {
        info("", thrown);
    }

    @Override
    public void info(String msg, Throwable thrown)
    {
        _logger.info(msg, thrown);
    }

    @Override
    public void debug(String msg, Object... args)
    {
        _logger.debug(msg, args);
    }

    @Override
    public void debug(String msg, long arg)
    {
        if (isDebugEnabled())
            _logger.debug(msg, new Object[]{new Long(arg)});
    }

    @Override
    public void debug(Throwable thrown)
    {
        debug("", thrown);
    }

    @Override
    public void debug(String msg, Throwable thrown)
    {
        _logger.debug(msg, thrown);
    }

    @Override
    public boolean isDebugEnabled()
    {
        return _logger.isDebugEnabled();
    }

    @Override
    public void setDebugEnabled(boolean enabled)
    {
        warn("setDebugEnabled not implemented", null, null);
    }

    /**
     * Create a Child Logger of this Logger.
     */
    @Override
    protected Logger newLogger(String fullname)
    {
        return new Slf4jLog(fullname);
    }

    @Override
    public void ignore(Throwable ignored)
    {
        if (Log.isIgnored())
        {
            debug(Log.IGNORED, ignored);
        }
    }

    @Override
    public String toString()
    {
        return _logger.toString();
    }
}
