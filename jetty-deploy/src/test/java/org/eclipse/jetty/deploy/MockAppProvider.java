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
package org.eclipse.jetty.deploy;

import java.io.File;

import org.eclipse.jetty.deploy.test.MavenTestingUtils;
import org.eclipse.jetty.util.component.AbstractLifeCycle;

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
        File war = new File(webappsDir,name);
        App app = new App(deployMan,"mock-" + name,war);
        this.deployMan.addApp(app);
    }
}
