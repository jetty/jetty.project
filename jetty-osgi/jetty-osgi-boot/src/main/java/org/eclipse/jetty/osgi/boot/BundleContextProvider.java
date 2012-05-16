// ========================================================================
// Copyright (c) 2012 Intalio, Inc.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================
package org.eclipse.jetty.osgi.boot;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.deploy.AppProvider;
import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.deploy.util.FileID;
import org.eclipse.jetty.osgi.boot.internal.serverfactory.ServerInstanceWrapper;
import org.eclipse.jetty.osgi.boot.internal.webapp.BundleFileLocatorHelperFactory;
import org.eclipse.jetty.osgi.boot.internal.webapp.OSGiWebappClassLoader;
import org.eclipse.jetty.osgi.boot.utils.OSGiClassLoader;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.xml.XmlConfiguration;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;



/**
 * BundleContextProvider
 *
 * Handles deploying bundles that define a context xml file for configuring them.
 * 
 * Also able to deploy non-webapp, generic ContextHandlers that have been registered as an osgi service.
 */
public class BundleContextProvider extends AbstractLifeCycle implements AppProvider, BundleProvider, ServiceProvider
{    
    private static final Logger LOG = Log.getLogger(BundleContextProvider.class);
    
    
    private DeploymentManager _deploymentManager;

    private Map<String, App> _appMap = new HashMap<String, App>();
    
    private Map<Bundle, List<App>> _bundleMap = new HashMap<Bundle, List<App>>();
    
    private ServerInstanceWrapper _wrapper;
    
    private ServiceRegistration _serviceRegForBundles;
    
    private ServiceRegistration _serviceRegForServices;
    
    
    
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
        private ContextHandler _contextHandler;
        private OSGiClassLoader _classloader;
        
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

        
        public ContextHandler getContextHandler()
        throws Exception
        {
            if (_contextHandler != null)
                return _contextHandler;
            
            createContextHandler();
            return _contextHandler;
        }
        
        public void createContextHandler()
        throws Exception
        {
            if (_contextFile == null)
                throw new IllegalStateException ("No contextFile");
            
            //apply the contextFile, creating the ContextHandler, the DeploymentManager will register it in the ContextHandlerCollection
            Resource res = null;
            
            //try to find the context file in the filesystem
            if (_contextFile.startsWith("/"))
               res = getFileAsResource(_contextFile);
            
            //try to find it relative to jetty home
            if (res == null)
            {
                //See if the specific server we are related to has jetty.home set
                String jettyHome = (String)_wrapper.getServer().getAttribute(OSGiServerConstants.JETTY_HOME);
                if (jettyHome != null)
                   res = getFileAsResource(jettyHome, _contextFile);
                
                //try to see if a SystemProperty for jetty.home is set
                if (res == null)
                {
                    jettyHome =  System.getProperty(OSGiServerConstants.JETTY_HOME);

                    if (jettyHome.startsWith("\"") || jettyHome.startsWith("'"))
                        jettyHome = jettyHome.substring(1);
                    if (jettyHome.endsWith("\"") || (jettyHome.endsWith("'")))
                    jettyHome = jettyHome.substring(0,jettyHome.length()-1);
                    
                   res = getFileAsResource(jettyHome, _contextFile);
                }
            }

            //try to find it relative to the bundle in which it is being deployed
            if (res == null)
            {
                if (_contextFile.startsWith("./"))
                    _contextFile = _contextFile.substring(1);

                if (!_contextFile.startsWith("/"))
                    _contextFile = "/" + _contextFile;

                URL contextURL = _bundle.getEntry(_contextFile);
                if (contextURL != null)
                    res = Resource.newResource(contextURL);
            }

            if (res != null)
            { 
                ClassLoader cl = Thread.currentThread().getContextClassLoader();

                LOG.debug("Context classloader = " + cl);
                try
                {
                    //Use a classloader that knows about the common jetty parent loader, and also the bundle
                    
                    OSGiClassLoader classLoader = new OSGiClassLoader(_wrapper.getParentClassLoaderForWebapps(), 
                                                                       _bundle);
                    Thread.currentThread().setContextClassLoader(classLoader);
                    XmlConfiguration xmlConfiguration = new XmlConfiguration(res.getInputStream());
                    HashMap properties = new HashMap();
                    properties.put("Server", _wrapper.getServer());

                    // insert the bundle's location as a property.
                    //setThisBundleHomeProperty(_bundle, properties, overrideBundleInstallLocation);
                    xmlConfiguration.getProperties().putAll(properties);

                    if (_contextHandler == null)
                        _contextHandler = (ContextHandler) xmlConfiguration.configure();
                    else
                        xmlConfiguration.configure(_contextHandler);

                    _contextHandler.setClassLoader(classLoader);
                }
                finally
                {
                    Thread.currentThread().setContextClassLoader(cl);
                }
            }
        }

        
    

        /**
         * Set the property &quot;this.bundle.install&quot; to point to the location
         * of the bundle. Useful when <SystemProperty name="this.bundle.home"/> is
         * used.
         */
        private void setThisBundleHomeProperty(Bundle bundle, HashMap<String, Object> properties, String overrideBundleInstallLocation)
        {
            try
            {
                File location = 
                    overrideBundleInstallLocation != null ? 
                                                            new File(overrideBundleInstallLocation) : 
                                                            BundleFileLocatorHelperFactory.getFactory().getHelper().getBundleInstallLocation(bundle);
                properties.put("this.bundle.install", location.getCanonicalPath());
                properties.put("this.bundle.install.url", bundle.getEntry("/").toString());
            }
            catch (Throwable t)
            {
                LOG.warn("Unable to set 'this.bundle.install' " + " for the bundle " + bundle.getSymbolicName(), t);
            }
        }


        private Resource getFileAsResource (String dir, String file)
        {
            Resource r = null;
            try
            {
                File asFile = new File (dir, file);
                if (asFile.exists())
                    r = Resource.newResource(asFile);
            }
            catch (Exception e)
            {
                r = null;
            } 
            return r;
        }
        
        private Resource getFileAsResource (String file)
        {
            Resource r = null;
            try
            {
                File asFile = new File (file);
                if (asFile.exists())
                    r = Resource.newResource(asFile);
            }
            catch (Exception e)
            {
                r = null;
            } 
            return r;
        }
    }
    
    
    /* ------------------------------------------------------------ */
    public BundleContextProvider(ServerInstanceWrapper wrapper)
    {
        _wrapper = wrapper;
    }
    
    
    /* ------------------------------------------------------------ */
    @Override
    protected void doStart() throws Exception
    {
        //register as an osgi service for deploying contexts defined in a bundle, advertising the name of the jetty Server instance we are related to
        Dictionary<String,String> properties = new Hashtable<String,String>();
        properties.put(OSGiServerConstants.MANAGED_JETTY_SERVER_NAME, _wrapper.getManagedServerName());
        _serviceRegForBundles = FrameworkUtil.getBundle(this.getClass()).getBundleContext().registerService(BundleProvider.class.getName(), this, properties);
        //register as an osgi service for deploying contexts, advertising the name of the jetty Server instance we are related to
        _serviceRegForServices = FrameworkUtil.getBundle(this.getClass()).getBundleContext().registerService(ServiceProvider.class.getName(), this, properties);
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
        
        if (_serviceRegForServices != null)
        {
            try
            {
                _serviceRegForServices.unregister();
            }
            catch (Exception e)
            {
                LOG.warn(e);
            }
        }
        super.doStop();
    }


    /* ------------------------------------------------------------ */
    public void setDeploymentManager(DeploymentManager deploymentManager)
    {
        _deploymentManager = deploymentManager;
    }
    
    
    /* ------------------------------------------------------------ */
    /** 
     * @see org.eclipse.jetty.deploy.AppProvider#createContextHandler(org.eclipse.jetty.deploy.App)
     */
    public ContextHandler createContextHandler(App app) throws Exception
    {
        if (app == null)
            return null;
        if (!(app instanceof BundleApp))
            throw new IllegalStateException(app+" is not a BundleApp");
        
        //Create a ContextHandler suitable to deploy in OSGi
        return ((BundleApp)app).getContextHandler();
    }

    /* ------------------------------------------------------------ */
    /**
     * @param bundle
     * @param contextFiles
     * @return
     */
    public boolean bundleAdded (Bundle bundle)
    {
        if (bundle == null)
            return false;

        String contextFiles  = (String)bundle.getHeaders().get(OSGiWebappConstants.JETTY_CONTEXT_FILE_PATH);
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
            BundleApp app = new BundleApp(_deploymentManager, this, originId, bundle, contextFile);
            _appMap.put(originId,app);
            List<App> apps = _bundleMap.get(bundle);
            if (apps == null)
            {
                apps = new ArrayList<App>();
                _bundleMap.put(bundle, apps);
            }
            apps.add(app);
            _deploymentManager.addApp(app);
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
    public boolean bundleRemoved (Bundle bundle)
    {
        List<App> apps = _bundleMap.remove(bundle);
        boolean removed = false;
        if (apps != null)
        {
            for (App app:apps)
            {
                _appMap.remove(app.getOriginId());
                _deploymentManager.removeApp(app);
                removed = true;
            }
        }
        return removed; //true if even 1 context was removed associated with this bundle
    }
    
    
    public boolean serviceAdded (ServiceReference serviceRef, ContextHandler context)
    {
        //TODO deploy a contexthandler that some other package has created as a service
        if (context == null || serviceRef == null)
            return false;
        
        return false;
    }
    
    
    public boolean serviceRemoved (ServiceReference serviceRef, ContextHandler context)
    {
        //TODO remove a contexthandler that was a service
        if (context == null || serviceRef == null)
            return false;
        
      
        
        return false;
    }
}
