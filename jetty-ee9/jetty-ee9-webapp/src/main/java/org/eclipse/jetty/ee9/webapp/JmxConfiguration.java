//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.webapp;

import org.eclipse.jetty.util.Loader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>This configuration configures the {@link WebAppContext} system/server classes
 * so that web applications are able to see the {@code org.eclipse.jetty.jmx} and
 * {@code org.eclipse.jetty.util.annotation} packages and sub-packages.</p>
 * <p>This class is defined in the webapp module because it implements the
 * {@link Configuration} interface, which is unknown to the jmx module.</p>
 */
public class JmxConfiguration extends AbstractConfiguration
{
    private static final Logger LOG = LoggerFactory.getLogger(JmxConfiguration.class);

    public JmxConfiguration()
    {
        addDependents(WebXmlConfiguration.class, MetaInfConfiguration.class, WebInfConfiguration.class);
        protectAndExpose("org.eclipse.jetty.util.annotation.", "org.eclipse.jetty.jmx.");
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
            if (LOG.isTraceEnabled())
                LOG.trace("IGNORED", e);
            return false;
        }
    }
}
