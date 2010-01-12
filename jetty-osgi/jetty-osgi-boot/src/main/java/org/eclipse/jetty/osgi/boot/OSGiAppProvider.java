package org.eclipse.jetty.osgi.boot;


import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.deploy.AppProvider;
import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.component.AbstractLifeCycle;

public class OSGiAppProvider extends AbstractLifeCycle implements AppProvider
{
    DeploymentManager _manager;
    Map<ContextHandler,OSGiApp> _apps = new ConcurrentHashMap<ContextHandler, OSGiApp>();
    
    public ContextHandler createContextHandler(App app) throws Exception
    {
        // return pre-created Context
        return ((OSGiApp)app)._context;
    }

    public void setDeploymentManager(DeploymentManager deploymentManager)
    {
        _manager=deploymentManager;
    }

    public void addContext(ContextHandler context) throws Exception
    {
        // TODO apply configuration specific to this provider
        
        // wrap context as an App
        OSGiApp app = new OSGiApp(_manager,this,context.getDisplayName(),context);
        _apps.put(context,app);
        _manager.addApp(app);
    }

    public void addContext(String contextxml) throws Exception
    {
        // TODO apply configuration specific to this provider
        // TODO construct ContextHandler
        ContextHandler context=null;
        // wrap context as an App
        OSGiApp app = new OSGiApp(_manager,this,context.getDisplayName(),context);
        _apps.put(context,app);
        _manager.addApp(app);
    }
    
    public void removeContext(ContextHandler context) throws Exception
    {
        // wrap context as an App
        OSGiApp app = _apps.get(context);
        
    }
    
    
    class OSGiApp extends App
    {
        final ContextHandler _context;
        public OSGiApp(DeploymentManager manager, AppProvider provider, String originId, ContextHandler context)
        {
            super(manager,provider,originId);
            _context=context;
        }
    }
}
