//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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
 * <p>Jetty Servlets Configuration</p>
 * <p>This configuration configures the WebAppContext server/system classes to
 * expose the jetty utility servlets if they are on the server classpath.
 * </p>
 */
public class ServletsConfiguration extends AbstractConfiguration
{
    private static final Logger LOG = LoggerFactory.getLogger(ServletsConfiguration.class);

    public ServletsConfiguration()
    {
        addDependencies(WebXmlConfiguration.class, MetaInfConfiguration.class, WebInfConfiguration.class, WebAppConfiguration.class);
        addDependents(JettyWebXmlConfiguration.class);
        protectAndExpose();
        protect("org.eclipse.jetty.ee9.servlets.PushCacheFilter", //must be loaded by container classpath
            "org.eclipse.jetty.ee9.servlets.PushSessionCacheFilter" //must be loaded by container classpath
        );
        expose("org.eclipse.jetty.ee9.servlets."); // don't hide jetty servlets
    }

    @Override
    public boolean isAvailable()
    {
        try
        {
            return Loader.loadClass("org.eclipse.jetty.ee9.servlets.PushCacheFilter") != null;
        }
        catch (Throwable e)
        {
            LOG.trace("IGNORED", e);
            return false;
        }
    }
}
