package org.eclipse.jetty.osgi.boot;

import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.deploy.AppProvider;
import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.component.AbstractLifeCycle;

public class OSGiAppProvider extends AbstractLifeCycle implements AppProvider
{
    DeploymentManager _manager;
    
    public ContextHandler createContextHandler(App app) throws Exception
    {
        return null;
    }

    public void setDeploymentManager(DeploymentManager deploymentManager)
    {
        _manager=deploymentManager;
    }

}
