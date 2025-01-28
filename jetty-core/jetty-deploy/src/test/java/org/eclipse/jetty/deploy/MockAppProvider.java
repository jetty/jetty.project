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

package org.eclipse.jetty.deploy;

import java.nio.file.Path;

import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.FileID;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.Environment;

public class MockAppProvider extends AbstractLifeCycle
{
    private DeploymentManager deployMan;
    private Path webappsDir;

    public String getEnvironmentName()
    {
        return Environment.ensure("mock").getName();
    }

    public void setDeploymentManager(DeploymentManager deploymentManager)
    {
        this.deployMan = deploymentManager;
    }

    @Override
    public void doStart()
    {
        this.webappsDir = MavenTestingUtils.getTestResourcePathDir("webapps");
    }

    public App createWebapp(String name)
    {
        String basename = FileID.getBasename(name);
        MockApp app = new MockApp(basename);
        app.setContextHandler(createContextHandler(app));
        this.deployMan.addApp(app);
        return app;
    }

    public ContextHandler createContextHandler(App app)
    {
        ContextHandler contextHandler = new ContextHandler();

        String name = app.getName();
        Path war = webappsDir.resolve(name + ".war");

        String contextPath = war.toString();

        if (FileID.isWebArchive(war))
        {
            // Context Path is the same as the archive.
            contextPath = FileID.getBasename(war);
        }

        // special case of archive named "root" is / context-path
        if (contextPath.equalsIgnoreCase("root"))
            contextPath = "/";

        // Ensure "/" is Prepended to all context paths.
        if (contextPath.charAt(0) != '/')
            contextPath = "/" + contextPath;

        // Ensure "/" is Not Trailing in context paths.
        if (contextPath.endsWith("/"))
            contextPath = contextPath.substring(0, contextPath.length() - 1);

        contextHandler.setContextPath(contextPath);

        return contextHandler;
    }

    @Override
    public String toString()
    {
        return String.format("MockAppProvider@%x:%s", hashCode(), getEnvironmentName());
    }
}
