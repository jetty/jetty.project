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
package org.eclipse.jetty.webapp.deploy;

import java.util.logging.Logger;

import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.deploy.AppLifeCycle;
import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.deploy.graph.Node;

public class DeployAuditLogBinding implements AppLifeCycle.Binding
{
    private Logger logger = Logger.getLogger("audit.deploy");

    public String[] getBindingTargets()
    {
        return new String[]
        { "*" };
    }

    public void processBinding(Node node, App app, DeploymentManager deploymentManager) throws Exception
    {
        logger.info("Reached LifeCycle " + node.getName() + " on app " + app.getOriginId());
    }
}
