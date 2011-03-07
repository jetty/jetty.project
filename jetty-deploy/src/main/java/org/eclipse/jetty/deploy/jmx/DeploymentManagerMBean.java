package org.eclipse.jetty.deploy.jmx;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.deploy.AppProvider;
import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.jmx.ObjectMBean;
import org.eclipse.jetty.server.handler.ContextHandler;

public class DeploymentManagerMBean extends ObjectMBean
{
    private final DeploymentManager _manager;
    
    public DeploymentManagerMBean(Object managedObject)
    {
        super(managedObject);
        _manager=(DeploymentManager)managedObject;
    }
    
    public Collection<String> getApps()
    {
        List<String> apps=new ArrayList<String>();
        for (App app: _manager.getApps())
            apps.add(app.getOriginId());
        return apps;
    }
    
    public Collection<ContextHandler> getContexts() throws Exception
    {
        List<ContextHandler> apps=new ArrayList<ContextHandler>();
        for (App app: _manager.getApps())
            apps.add(app.getContextHandler());
        return apps;
    }
    
    public Collection<AppProvider> getAppProviders()
    {
        return _manager.getAppProviders();
    }
}
