package org.eclipse.jetty.osgi.boot;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.deploy.AppProvider;
import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.osgi.framework.Bundle;

public class BundleContextProvider extends AbstractLifeCycle implements AppProvider
{    
    private DeploymentManager _deploymentManager;

    private Map<String, App> _appMap = new HashMap<String, App>();
    
    
    /* ------------------------------------------------------------ */
    /**
     * BundleApp
     *
     *
     */
    public class BundleApp extends App
    {
        private String _contextFile;
        private Bundle _bundle;
        
        public BundleApp(DeploymentManager manager, AppProvider provider, String originId, Bundle bundle, String contextFile)
        {
            super(manager, provider, originId);
            _bundle = bundle;
            _contextFile = contextFile;
        }
        
        
        public String getContextFile ()
        {
            return _contextFile;
        }
    }
    
    
    /* ------------------------------------------------------------ */
    public void setDeploymentManager(DeploymentManager deploymentManager)
    {
        _deploymentManager = deploymentManager;
    }
    
    
    /* ------------------------------------------------------------ */
    public ContextHandler createContextHandler(App app) throws Exception
    {
        //apply the contextFile, creating the ContextHandler, the DeploymentManager will register it in the ContextHandlerCollection
        return null;
    }

    /* ------------------------------------------------------------ */
    public void bundleAdded (Bundle bundle, String contextFiles)
    {
        if (contextFiles == null)
            return;

        //bundle defines OSGiWebappConstants.JETTY_CONTEXT_FILE_PATH header,
        //a comma separated list of context xml files that each define a ContextHandler (could be WebAppContexts)       
        String[] tmp = contextFiles.split(",;");
        for (String contextFile : tmp)
        {
            String originId = bundle.getSymbolicName() + "-" + bundle.getVersion().toString() + "-"+contextFile;
            BundleApp app = new BundleApp(_deploymentManager, this, originId, bundle, contextFile);
            _appMap.put(originId,app);
            _deploymentManager.addApp(app);
        }
    }

}
