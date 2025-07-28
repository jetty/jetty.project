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

import org.eclipse.jetty.maven.AbstractHomeForker;

/**
 * JettyHomeForker
 *
 * Unpacks a jetty-home and configures it with a base that allows it
 * to run an unassembled webapp.
 */
public class JettyHomeForker extends AbstractHomeForker
{
    protected MavenWebAppContext webApp;

    public JettyHomeForker(String javaPath)
    {
        super(javaPath);
        environment = "ee9";
    }

    public void setWebApp(MavenWebAppContext webApp)
    {
        this.webApp = webApp;
    }

    protected void redeployWebApp()
        throws Exception
    {
        generateWebAppPropertiesFile();
        webappPath.resolve("maven.xml").toFile().setLastModified(System.currentTimeMillis());
    }

    protected void generateWebAppPropertiesFile()
        throws Exception
    {
        WebAppPropertyConverter.toProperties(webApp, etcPath.resolve("maven.props").toFile(), contextXml);
    }
}
