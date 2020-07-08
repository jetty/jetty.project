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

package org.eclipse.jetty.servlet.listener;

import javax.servlet.FilterChain;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.servlet.BaseHolder;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

/**
 * Listener bean for obtaining events related to servlet initialization / timing, and
 * calls (to Filters and Servlets) within the defined `ServletContextHandler`.
 * <p>
 * This supplements the {@link org.eclipse.jetty.server.HttpChannel.Listener}
 * with events that represent behaviors within a specific `ServletContextHandler`.
 * </p>
 * <p>
 * If there is a Bean present on the `ServletContextHandler` implementing this
 * Listener it is used, otherwise the Server level Bean is used if present.
 * If no bean is discovered, no listener is notified.
 * </p>
 */
public interface ServletMetricsListener
{
    /**
     * Timing for the entire ServletContextHandler startup + initialization.
     *
     * @param servletContextHandler the specific context that was started + initialized.
     * @param durationNanoSeconds the duration in nanoseconds for this startup.
     */
    void onServletContextInitTiming(ServletContextHandler servletContextHandler, long durationNanoSeconds);

    /**
     * Timing for a specific Filter / Servlet / Listener that was initialized.
     *
     * @param servletContextHandler the specific context that was started + initialized.
     * @param holder this can be a {@link FilterHolder}, a {@link org.eclipse.jetty.servlet.ServletHolder}, or a {@link org.eclipse.jetty.servlet.ListenerHolder}
     * @param durationNanoSeconds the duration in nanoseconds for this initialization.
     */
    void onServletContextInitTiming(ServletContextHandler servletContextHandler, BaseHolder<?> holder, long durationNanoSeconds);

    /**
     * Event indicating a specific {@link javax.servlet.Filter#doFilter(ServletRequest, ServletResponse, FilterChain)} ` was entered
     * from the Filter chain.
     *
     * @param servletContextHandler the specific context that this event occurred against.
     * @param filterHolder the filter that was entered
     * @param request the request that caused this filter to be entered.
     */
    void onFilterEnter(ServletContextHandler servletContextHandler, FilterHolder filterHolder, Request request);

    /**
     * Event indicating a specific {@link javax.servlet.Filter#doFilter(ServletRequest, ServletResponse, FilterChain)} ` was exited
     * from the Filter chain.
     *
     * @param servletContextHandler the specific context that this event occurred against.
     * @param filterHolder the filter that was exited
     * @param request the request that caused this filter to be exited.
     */
    void onFilterExit(ServletContextHandler servletContextHandler, FilterHolder filterHolder, Request request);

    /**
     * Event indicating a specific {@link javax.servlet.Servlet#service(ServletRequest, ServletResponse)} ` was entered.
     *
     * @param servletContextHandler the specific context that this event occurred against.
     * @param servletHolder the servlet that was entered.
     * @param request the request that entered this servlet.
     */
    void onServletServiceEnter(ServletContextHandler servletContextHandler, ServletHolder servletHolder, Request request);

    /**
     * Event indicating a specific {@link javax.servlet.Servlet#service(ServletRequest, ServletResponse)} ` was exited.
     *
     * @param servletContextHandler the specific context that this event occurred against.
     * @param servletHolder the servlet that was exited.
     * @param request the request that exited this servlet.
     */
    void onServletServiceExit(ServletContextHandler servletContextHandler, ServletHolder servletHolder, Request request);
}
