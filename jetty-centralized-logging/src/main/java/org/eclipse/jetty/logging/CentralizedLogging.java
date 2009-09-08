// ========================================================================
// Copyright (c) Webtide LLC
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
//
// The Apache License v2.0 is available at
// http://www.apache.org/licenses/LICENSE-2.0.txt
//
// You may elect to redistribute this code under either of these licenses.
// ========================================================================
package org.eclipse.jetty.logging;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.webapp.Configuration;
import org.eclipse.jetty.webapp.WebAppContext;

/**
 * Jetty Centralized Logging bean.
 */
public class CentralizedLogging extends AbstractLifeCycle
{
    private Server server;
    private CentralizedWebAppLoggingConfiguration webAppConfiguration;
    private String configurationFilename;

    public CentralizedLogging()
    {
        System.err.println(CentralizedLogging.class.getName() + " <init>");
    }

    public String getConfigurationFilename()
    {
        return configurationFilename;
    }

    public void setConfigurationFilename(String filename)
    {
        this.configurationFilename = filename;
    }

    public Server getServer()
    {
        return server;
    }

    public void setServer(Server server)
    {
        this.server = server;
    }

    @Override
    protected void doStart() throws Exception
    {
        CentralizedWebAppLoggingConfiguration.setLoggerConfigurationFilename(configurationFilename);
        webAppConfiguration = new CentralizedWebAppLoggingConfiguration();

        @SuppressWarnings("unchecked")
        List<Configuration> config = (List<Configuration>)server.getAttribute(WebAppContext.SERVER_CONFIG);
        if (config == null)
        {
            config = new ArrayList<Configuration>();
        }
        config.add(webAppConfiguration);
        server.setAttribute(WebAppContext.SERVER_CONFIG,config);

        super.doStart();
    }
}
