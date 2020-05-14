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

package org.eclipse.jetty.websocket.server.config;

import java.util.ServiceLoader;

import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.webapp.AbstractConfiguration;
import org.eclipse.jetty.webapp.Configuration;
import org.eclipse.jetty.webapp.FragmentConfiguration;
import org.eclipse.jetty.webapp.MetaInfConfiguration;
import org.eclipse.jetty.webapp.WebAppConfiguration;
import org.eclipse.jetty.webapp.WebInfConfiguration;
import org.eclipse.jetty.webapp.WebXmlConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Websocket Configuration</p>
 * <p>This configuration configures the WebAppContext server/system classes to
 * be able to see the org.eclipse.jetty.websocket package.
 * This class is defined in the webapp package, as it implements the {@link Configuration} interface,
 * which is unknown to the websocket package.  However, the corresponding {@link ServiceLoader}
 * resource is defined in the websocket package, so that this configuration only be
 * loaded if the jetty-websocket jars are on the classpath.
 * </p>
 */
public class JettyWebSocketConfiguration extends AbstractConfiguration
{
    private static final Logger LOG = LoggerFactory.getLogger(JettyWebSocketConfiguration.class);

    public JettyWebSocketConfiguration()
    {
        addDependencies(WebXmlConfiguration.class, MetaInfConfiguration.class, WebInfConfiguration.class, FragmentConfiguration.class);

        if (isAvailable("org.eclipse.jetty.osgi.annotations.AnnotationConfiguration"))
            addDependents("org.eclipse.jetty.osgi.annotations.AnnotationConfiguration", WebAppConfiguration.class.getName());
        else if (isAvailable("org.eclipse.jetty.annotations.AnnotationConfiguration"))
            addDependents("org.eclipse.jetty.annotations.AnnotationConfiguration", WebAppConfiguration.class.getName());
        else
            throw new RuntimeException("Unable to add AnnotationConfiguration dependent (not present in classpath)");

        protectAndExpose(
            "org.eclipse.jetty.websocket.api.",
            "org.eclipse.jetty.websocket.server.",
            "org.eclipse.jetty.websocket.util.server."); // For WebSocketUpgradeFilter

        hide("org.eclipse.jetty.server.internal.",
            "org.eclipse.jetty.server.config.");
    }

    @Override
    public boolean isAvailable()
    {
        return isAvailable("org.eclipse.jetty.websocket.common.JettyWebSocketFrame");
    }

    private boolean isAvailable(String classname)
    {
        try
        {
            return Loader.loadClass(classname) != null;
        }
        catch (Throwable e)
        {
            LOG.trace("IGNORED", e);
            return false;
        }
    }
}
