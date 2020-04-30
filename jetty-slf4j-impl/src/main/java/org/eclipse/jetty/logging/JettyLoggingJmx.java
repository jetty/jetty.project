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

package org.eclipse.jetty.logging;

import java.lang.management.ManagementFactory;
import javax.management.MBeanServer;
import javax.management.ObjectName;

public class JettyLoggingJmx
{
    public static void initialize(JettyLoggerConfiguration config, JettyLoggerFactory loggerFactory)
    {
        if (!config.getBoolean("org.eclipse.jetty.logging.jmx", false))
        {
            loggerFactory.getJettyLogger(JettyLoggingJmx.class.getName()).debug("JMX not enabled");
            return;
        }

        try
        {
            MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            String contextName = config.getString("org.eclipse.jetty.logging.jmx.contextName", "default");

            ObjectName objName = new ObjectName(JettyLoggerFactory.class.getName() + ":name=" + contextName);
            mbs.registerMBean(loggerFactory, objName);
        }
        catch (Throwable cause)
        {
            JettyLogger logger = loggerFactory.getJettyLogger(JettyLoggingJmx.class.getName());
            logger.warn("java.management not available.");
            logger.debug("java.management is not available", cause);
        }
    }
}
