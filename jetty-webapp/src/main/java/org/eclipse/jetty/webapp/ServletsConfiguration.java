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
 * <p>Jetty Servlets Configuration</p>
 * <p>This configuration configures the WebAppContext server/system classes to
 * expose the jetty utility servlets if they are on the server classpath.
 * </p>
 *
 */
public class ServletsConfiguration extends AbstractConfiguration
{
    public ServletsConfiguration()
    {
        addDependencies(WebXmlConfiguration.class, MetaInfConfiguration.class, WebInfConfiguration.class, WebAppConfiguration.class);
        addDependents(JettyWebXmlConfiguration.class);
        protectAndExpose();
        protect("org.eclipse.jetty.servlets.PushCacheFilter", //must be loaded by container classpath
                "org.eclipse.jetty.servlets.PushSessionCacheFilter" //must be loaded by container classpath
                );
        expose("org.eclipse.jetty.servlets."); // don't hide jetty servlets
    }
}
