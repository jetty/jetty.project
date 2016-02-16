//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.deploy;

import java.io.File;

import org.eclipse.jetty.deploy.util.FileID;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.WebAppContext;

public class MockAppProvider extends AbstractLifeCycle implements AppProvider
{
    private DeploymentManager deployMan;
    private File webappsDir;

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
        App app = new App(deployMan,this,"mock-" + name);
        this.deployMan.addApp(app);
    }

    public ContextHandler createContextHandler(App app) throws Exception
    {
        WebAppContext context = new WebAppContext();

        File war = new File(webappsDir,app.getOriginId().substring(5));
        context.setWar(Resource.newResource(Resource.toURL(war)).toString());

        String path = war.getName();
        
        if (FileID.isWebArchiveFile(war))
        {
            // Context Path is the same as the archive.
            path = path.substring(0,path.length() - 4);
        }
        
        // special case of archive (or dir) named "root" is / context
        if (path.equalsIgnoreCase("root") || path.equalsIgnoreCase("root/"))
            path = URIUtil.SLASH;

        // Ensure "/" is Prepended to all context paths.
        if (path.charAt(0) != '/')
            path = "/" + path;

        // Ensure "/" is Not Trailing in context paths.
        if (path.endsWith("/") && path.length() > 0)
            path = path.substring(0,path.length() - 1);

        context.setContextPath(path);
        
        return context;
    }
}
