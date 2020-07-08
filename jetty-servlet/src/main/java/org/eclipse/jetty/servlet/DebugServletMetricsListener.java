//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.servlet;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.servlet.listener.ServletMetricsListener;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * A Debug Logging {@link ServletMetricsListener}.
 */
public class DebugServletMetricsListener implements ServletMetricsListener
{
    protected final Logger logger;

    public DebugServletMetricsListener()
    {
        this(Log.getLogger(DebugServletMetricsListener.class));
    }

    public DebugServletMetricsListener(Logger logger)
    {
        this.logger = logger;
    }

    @Override
    public void onServletContextInitTiming(ServletContextHandler servletContextHandler, long durationNanoSeconds)
    {
        logger.debug("App Context Init: Duration: {} - App {}", toHumanReadableMilliSeconds(durationNanoSeconds), servletContextHandler);
    }

    @Override
    public void onServletContextInitTiming(ServletContextHandler servletContextHandler, BaseHolder<?> holder, long durationNanoSeconds)
    {
        logger.debug("App Component Init: Duration: {} - Component: {} - App: {}", toHumanReadableMilliSeconds(durationNanoSeconds), holder, servletContextHandler);
    }

    @Override
    public void onFilterEnter(ServletContextHandler servletContextHandler, FilterHolder filterHolder, Request request)
    {
        logger.debug("Filter Entered: {} - App: {}", filterHolder, servletContextHandler);
    }

    @Override
    public void onFilterExit(ServletContextHandler servletContextHandler, FilterHolder filterHolder, Request request)
    {
        logger.debug("Filter Exited: {} - App: {}", filterHolder, servletContextHandler);
    }

    @Override
    public void onServletServiceEnter(ServletContextHandler servletContextHandler, ServletHolder servletHolder, Request request)
    {
        logger.debug("Servlet Entered: {} - App: {}", servletHolder, servletContextHandler);
    }

    @Override
    public void onServletServiceExit(ServletContextHandler servletContextHandler, ServletHolder servletHolder, Request request)
    {
        logger.debug("Servlet Exited: {} - App: {}", servletHolder, servletContextHandler);
    }

    protected static String toHumanReadableMilliSeconds(long durationNanoSeconds)
    {
        double durationMilliSeconds = (double)durationNanoSeconds / 1_000_000;
        return String.format("%.4f ms", durationMilliSeconds);
    }
}
