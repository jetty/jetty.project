package org.eclipse.jetty.servlet;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.servlet.listener.ServletMetricsListener;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

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
