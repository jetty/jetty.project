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

package org.eclipse.jetty.webapp;

import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.listener.ServletMetricsListener;

/**
 * Listener bean for obtaining events related to webapp initialization / timing, and
 * calls (to Filters and Servlets) within the defined `WebAppContext`.
 * <p>
 * This supplements the {@link org.eclipse.jetty.server.HttpChannel.Listener}
 * with events that represent behaviors within a specific `ServletContextHandler`.
 * </p>
 * <p>
 * If there is a Bean present on the `WebAppContext` implementing this
 * Listener it is used, otherwise the Server level bean is used if present.
 * If no bean is discovered, no listener is notified.
 * </p>
 */
public interface WebAppMetricsListener extends ServletMetricsListener
{
    enum ConfigurationStep
    {
        PRE, MAIN, POST
    }

    /**
     * Timing for a specific {@link Configuration} being applied to the {@link WebAppContext}
     *
     * @param context the specific context that was the configuration was applied to
     * @param configuration the configuration that was applied
     * @param configurationStep the configuration step
     * @param durationNanoSeconds the duration in nanoseconds for this startup
     */
    void onWebAppConfigureTiming(WebAppContext context, Configuration configuration, ConfigurationStep configurationStep, long durationNanoSeconds);

    /**
     * Timing for a specific {@link WebAppContext}.
     *
     * <p>
     * This is similar to {@link ServletMetricsListener#onServletContextInitTiming(ServletContextHandler, long)}
     * but also includes the preconfigure / postconfigure timings for the WebApp itself.
     * This often includes things like the Bytecode / Annotation scanning in its overall timing.
     * </p>
     *
     * @param context the specific context that was started / initialized
     * @param durationNanoSeconds the duration in nanoseconds for this startup
     */
    void onWebAppStartTiming(WebAppContext context, long durationNanoSeconds);
}
