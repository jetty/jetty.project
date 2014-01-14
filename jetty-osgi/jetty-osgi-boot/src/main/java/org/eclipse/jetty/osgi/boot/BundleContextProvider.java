//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.osgi.boot;

import java.util.ArrayList;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.osgi.boot.internal.serverfactory.ServerInstanceWrapper;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceRegistration;



/**
 * BundleContextProvider
 *
 * Handles deploying OSGi bundles that define a context xml file for configuring them.
 * 
 *
 */
public class BundleContextProvider extends AbstractContextProvider implements BundleProvider
{    
    private static final Logger LOG = Log.getLogger(AbstractContextProvider.class);
    

    private Map<String, App> _appMap = new HashMap<String, App>();
    
    private Map<Bundle, List<App>> _bundleMap = new HashMap<Bundle, List<App>>();
    
    private ServiceRegistration _serviceRegForBundles;
    

    
  
    /* ------------------------------------------------------------ */
    public BundleContextProvider(ServerInstanceWrapper wrapper)
    {
        super(wrapper);
    }
    
    
    /* ------------------------------------------------------------ */
    @Override
    protected void doStart() throws Exception
    {
        //register as an osgi service for deploying contexts defined in a bundle, advertising the name of the jetty Server instance we are related to
        Dictionary<String,String> properties = new Hashtable<String,String>();
        properties.put(OSGiServerConstants.MANAGED_JETTY_SERVER_NAME, getServerInstanceWrapper().getManagedServerName());
        _serviceRegForBundles = FrameworkUtil.getBundle(this.getClass()).getBundleContext().registerService(BundleProvider.class.getName(), this, properties);
        super.doStart();
    }

    /* ------------------------------------------------------------ */
    @Override
    protected void doStop() throws Exception
    {
        //unregister ourselves
        if (_serviceRegForBundles != null)
        {
            try
            {
                _serviceRegForBundles.unregister();
            }
            catch (Exception e)
            {
                LOG.warn(e);
            }
        }
    }




    /* ------------------------------------------------------------ */
    /**
     * @param bundle
     * @param contextFiles
     * @return
     */
    public boolean bundleAdded (Bundle bundle) throws Exception
    {
        if (bundle == null)
            return false;

        String contextFiles  = (String)bundle.getHeaders().get(OSGiWebappConstants.JETTY_CONTEXT_FILE_PATH);
        if (contextFiles == null)
            contextFiles = (String)bundle.getHeaders().get(OSGiWebappConstants.SERVICE_PROP_CONTEXT_FILE_PATH);
        
        if (contextFiles == null)
            return false;
        
        boolean added = false;
        //bundle defines JETTY_CONTEXT_FILE_PATH header,
        //a comma separated list of context xml files that each define a ContextHandler
        //TODO: (could be WebAppContexts)       
        String[] tmp = contextFiles.split(",;");
        for (String contextFile : tmp)
        {
            String originId = bundle.getSymbolicName() + "-" + bundle.getVersion().toString() + "-"+contextFile;
            OSGiApp app = new OSGiApp(getDeploymentManager(), this, originId, bundle, contextFile);
            _appMap.put(originId,app);
            List<App> apps = _bundleMap.get(bundle);
            if (apps == null)
            {
                apps = new ArrayList<App>();
                _bundleMap.put(bundle, apps);
            }
            apps.add(app);
            getDeploymentManager().addApp(app);
            added = true;
        }

        return added; //true if even 1 context from this bundle was added
    }
    
    
    /* ------------------------------------------------------------ */
    /** 
     * Bundle has been removed. If it was a context we deployed, undeploy it.
     * @param bundle
     * 
     * @return true if this was a context we had deployed, false otherwise
     */
    public boolean bundleRemoved (Bundle bundle) throws Exception
    {
        List<App> apps = _bundleMap.remove(bundle);
        boolean removed = false;
        if (apps != null)
        {
            for (App app:apps)
            {
                _appMap.remove(app.getOriginId());
                getDeploymentManager().removeApp(app);
                removed = true;
            }
        }
        return removed; //true if even 1 context was removed associated with this bundle
    }
    
   
}
