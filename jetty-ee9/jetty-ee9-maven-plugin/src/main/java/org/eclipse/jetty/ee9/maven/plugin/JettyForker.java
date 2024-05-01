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

package org.eclipse.jetty.ee9.maven.plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.maven.AbstractForker;
import org.eclipse.jetty.maven.AbstractServerForker;
import org.eclipse.jetty.maven.PluginLog;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Jetty;

/**
 * JettyForker
 *
 * Uses quickstart to generate a webapp and forks a process to run it.
 */
public class JettyForker extends AbstractServerForker
{
    protected MavenWebAppContext webApp;
    QuickStartGenerator generator;

    public JettyForker()
    {
        executionClassName = JettyForkedChild.class.getCanonicalName();
    }

    public void setWebApp(MavenWebAppContext webApp)
    {
        this.webApp = webApp;
    }

    @Override
    public void generateWebApp()
        throws Exception
    {
        //Run the webapp to create the quickstart file and properties file
        generator = new QuickStartGenerator(forkWebXml.toPath(), webApp);
        generator.setContextXml(contextXml);
        generator.setWebAppPropsFile(webAppPropsFile.toPath());
        generator.setServer(server);
        generator.generate();
    }

    protected void redeployWebApp()
        throws Exception 
    {
        //regenerating the quickstart will be noticed by the JettyForkedChild process
        //which will redeploy the webapp
        generator.generate();
    }
}
