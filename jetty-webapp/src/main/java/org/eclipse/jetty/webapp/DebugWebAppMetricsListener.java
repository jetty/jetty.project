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

import org.eclipse.jetty.servlet.DebugServletMetricsListener;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * A Debug Logging {@link WebAppMetricsListener}.
 */
public class DebugWebAppMetricsListener extends DebugServletMetricsListener implements WebAppMetricsListener
{
    public DebugWebAppMetricsListener()
    {
        this(Log.getLogger(DebugWebAppMetricsListener.class));
    }

    public DebugWebAppMetricsListener(Logger logger)
    {
        super(logger);
    }

    @Override
    public void onWebAppConfigureTiming(WebAppContext context, Configuration configuration, ConfigurationStep configurationStep, long durationNanoSeconds)
    {
        logger.debug("WebApp Configuration: Duration: {} - {}-Configuration: {} - App {}", toHumanReadableMilliSeconds(durationNanoSeconds), configurationStep, configuration, context);
    }

    @Override
    public void onWebAppStartTiming(WebAppContext context, long durationNanoSeconds)
    {
        logger.debug("WebApp Start: Duration: {} - App {}", toHumanReadableMilliSeconds(durationNanoSeconds), context);
    }
}
