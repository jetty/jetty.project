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

package org.eclipse.jetty.ee10.webapp;

/**
 * <p>WebApp Configuration</p>
 * <p>This configuration configures the WebAppContext server/system classes to
 * be able to see default servlets.
 * </p>
 */
public class WebAppConfiguration extends AbstractConfiguration
{
    public WebAppConfiguration()
    {
        addDependencies(WebXmlConfiguration.class, MetaInfConfiguration.class, WebInfConfiguration.class);
        addDependents(JettyWebXmlConfiguration.class);
        protectAndExpose(
            "org.eclipse.jetty.ee10.servlet.StatisticsServlet",
            "org.eclipse.jetty.ee10.servlet.DefaultServlet",
            "org.eclipse.jetty.ee10.servlet.NoJspServlet"
        );
        expose("org.eclipse.jetty.ee10.servlet.listener.");
    }
}
