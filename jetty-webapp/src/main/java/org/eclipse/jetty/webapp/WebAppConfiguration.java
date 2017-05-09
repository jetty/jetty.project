//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

/**
 * <p>WebApp Configuration</p>
 * <p>This configuration configures the WebAppContext server/system classes to
 * be able to see default servlets.
 * </p>
 *
 */
public class WebAppConfiguration extends AbstractConfiguration
{
    public WebAppConfiguration()
    {
        addDependencies(WebXmlConfiguration.class, MetaInfConfiguration.class, WebInfConfiguration.class);
        addDependents(JettyWebXmlConfiguration.class);
        protectAndExpose(
            "org.eclipse.jetty.util.log.",
            "org.eclipse.jetty.server.session.SessionData",
            "org.eclipse.jetty.servlet.StatisticsServlet", 
            "org.eclipse.jetty.servlet.DefaultServlet", 
            "org.eclipse.jetty.servlet.NoJspServlet",
            "org.eclipse.jetty.continuation.");
        expose( // TODO Evaluate why these are not protectAndExpose?
            "org.eclipse.jetty.servlet.listener.",
            "org.eclipse.jetty.alpn.");
    }
}
