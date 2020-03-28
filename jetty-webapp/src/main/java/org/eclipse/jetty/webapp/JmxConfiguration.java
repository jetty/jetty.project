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

package org.eclipse.jetty.webapp;

import java.util.ServiceLoader;

import org.eclipse.jetty.util.Loader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>JMX Configuration</p>
 * <p>This configuration configures the WebAppContext server/system classes to
 * be able to see the org.eclipse.jetty.jmx package.   This class is defined
 * in the webapp package, as it implements the {@link Configuration} interface,
 * which is unknown to the jmx package.  However, the corresponding {@link ServiceLoader}
 * resource is defined in the jmx package, so that this configuration only be
 * loaded if the jetty-jmx jars are on the classpath.
 * </p>
 */
public class JmxConfiguration extends AbstractConfiguration
{
    private static final Logger LOG = LoggerFactory.getLogger(JmxConfiguration.class);

    public JmxConfiguration()
    {
        addDependents(WebXmlConfiguration.class, MetaInfConfiguration.class, WebInfConfiguration.class);
        protectAndExpose("org.eclipse.jetty.jmx.");
    }

    @Override
    public boolean isAvailable()
    {
        try
        {
            return Loader.loadClass("org.eclipse.jetty.jmx.ObjectMBean") != null;
        }
        catch (Throwable e)
        {
            LOG.trace("IGNORED", e);
            return false;
        }
    }
}

