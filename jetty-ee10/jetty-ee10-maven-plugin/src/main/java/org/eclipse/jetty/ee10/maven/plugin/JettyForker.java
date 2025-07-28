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

package org.eclipse.jetty.ee10.maven.plugin;

import org.eclipse.jetty.maven.AbstractServerForker;

/**
 * JettyForker
 *
 * Uses quickstart to generate a webapp and forks a process to run it.
 */
public class JettyForker extends AbstractServerForker
{
    protected MavenWebAppContext webApp;
    QuickStartGenerator generator;

    public JettyForker(String javaPath)
    {
        super(javaPath);
        executionClassName = JettyForkedChild.class.getCanonicalName();
    }

    public void setWebApp(MavenWebAppContext app)
    {
        webApp = app;
    }

    @Override
    public void generateWebApp() throws Exception
    {
        //Run the webapp to create the quickstart file and properties file
        generator = new QuickStartGenerator(forkWebXml.toPath(), webApp);
        generator.setContextXml(contextXml);
        generator.setWebAppProps(webAppPropsFile.toPath());
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
