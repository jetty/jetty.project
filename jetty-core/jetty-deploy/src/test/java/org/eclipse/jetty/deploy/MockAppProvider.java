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

package org.eclipse.jetty.deploy;

import java.io.File;

import org.eclipse.jetty.deploy.util.FileID;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.component.AbstractLifeCycle;

public class MockAppProvider extends AbstractLifeCycle implements AppProvider
{
    private DeploymentManager deployMan;
    private File webappsDir;

    @Override
    public void setDeploymentManager(DeploymentManager deploymentManager)
    {
        this.deployMan = deploymentManager;
    }

    @Override
    public void doStart()
    {
        this.webappsDir = MavenTestingUtils.getTestResourceDir("webapps");
    }

    public void findWebapp(String name)
    {
        App app = new App(deployMan, this, null, "mock-" + name);
        this.deployMan.addApp(app);
    }

    @Override
    public ContextHandler createContextHandler(App app) throws Exception
    {
        ContextHandler contextHandler = new ContextHandler();

        File war = new File(webappsDir, app.getOriginId().substring(5));

        String path = war.getName();

        if (FileID.isWebArchiveFile(war))
        {
            // Context Path is the same as the archive.
            path = path.substring(0, path.length() - 4);
        }

        // special case of archive (or dir) named "root" is / context
        if (path.equalsIgnoreCase("root") || path.equalsIgnoreCase("root/"))
            path = URIUtil.SLASH;

        // Ensure "/" is Prepended to all context paths.
        if (path.charAt(0) != '/')
            path = "/" + path;

        // Ensure "/" is Not Trailing in context paths.
        if (path.endsWith("/") && path.length() > 0)
            path = path.substring(0, path.length() - 1);

        contextHandler.setContextPath(path);

        return contextHandler;
    }
}
