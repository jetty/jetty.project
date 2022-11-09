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

package org.eclipse.jetty.osgi;

import java.util.Dictionary;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;

import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.osgi.util.Util;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.StringUtil;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceRegistration;
import org.osgi.util.tracker.BundleTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BundleWebAppProvider
 * <p>
 * A Jetty Provider that knows how to deploy a WebApp contained inside a Bundle.
 */
public class BundleWebAppProvider extends AbstractContextProvider implements BundleProvider
{
    private static final Logger LOG = LoggerFactory.getLogger(AbstractContextProvider.class);

    /**
     * Map of Bundle to App. Used when a Bundle contains a webapp.
     */
    private Map<Bundle, App> _bundleMap = new HashMap<>();

    private ServiceRegistration _serviceRegForBundles;

    private WebAppTracker _webappTracker;

    public class WebAppTracker extends BundleTracker
    {
        protected String _managedServerName;

        public WebAppTracker(BundleContext bundleContext, String managedServerName)
        {
            super(bundleContext, Bundle.ACTIVE | Bundle.STOPPING, null);
            _managedServerName = managedServerName;
        }

        @Override
        public Object addingBundle(Bundle bundle, BundleEvent event)
        {
            try
            {
                String serverName = (String)bundle.getHeaders().get(OSGiServerConstants.MANAGED_JETTY_SERVER_NAME);
                
                if ((StringUtil.isBlank(serverName) && _managedServerName.equals(OSGiServerConstants.MANAGED_JETTY_SERVER_DEFAULT_NAME)) ||
                    (!StringUtil.isBlank(serverName) && (serverName.equals(_managedServerName))))
                {
                    if (bundleAdded(bundle))
                        return bundle;
                }
            }
            catch (Exception e)
            {
                LOG.warn("Unable to add bundle {}", bundle, e);
            }
            return null;
        }

        @Override
        public void removedBundle(Bundle bundle, BundleEvent event, Object object)
        {
            try
            {
                bundleRemoved(bundle);
            }
            catch (Exception e)
            {
                LOG.warn("Unable to remove bundle {}", bundle, e);
            }
        }
    }

    public BundleWebAppProvider(String environment, Server server, ContextFactory contextFactory)
    {
        super(environment, server, contextFactory);
    }

    @Override
    protected void doStart() throws Exception
    {
        String serverName = (String)getServer().getAttribute(OSGiServerConstants.MANAGED_JETTY_SERVER_NAME);
        _webappTracker = new WebAppTracker(FrameworkUtil.getBundle(this.getClass()).getBundleContext(), serverName);
        _webappTracker.open();
        //register as an osgi service for deploying bundles, advertising the name of the jetty Server instance we are related to
        Dictionary<String, String> properties = new Hashtable<>();
        properties.put(OSGiServerConstants.MANAGED_JETTY_SERVER_NAME, serverName);
        _serviceRegForBundles = FrameworkUtil.getBundle(this.getClass()).getBundleContext().registerService(BundleProvider.class.getName(), this, properties);
        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        _webappTracker.close();

        //unregister ourselves
        if (_serviceRegForBundles != null)
        {
            try
            {
                _serviceRegForBundles.unregister();
            }
            catch (Exception e)
            {
                LOG.warn("Unable to unregister {}", _serviceRegForBundles, e);
            }
        }

        super.doStop();
    }

    @Override
    public boolean isDeployable(Bundle bundle)
    {
        System.err.println("IS DEPLOYABLE BY ENVIRONMENT: " + super.isDeployable(bundle));
        //is it destined for my environment?
        if (!super.isDeployable(bundle))
            return false;
        
        //has a war path, could be a webapp
        if (!StringUtil.isBlank(Util.getManifestHeaderValue(OSGiWebappConstants.JETTY_WAR_RESOURCE_PATH, bundle.getHeaders())))
            return true;
       
        //has a context path header, could be a webapp
       if (!StringUtil.isBlank(Util.getManifestHeaderValue(OSGiWebappConstants.RFC66_WEB_CONTEXTPATH, bundle.getHeaders())))
           return true;
        
       //has a web.xml, could be a webapp
       if (bundle.getEntry("/WEB-INF/web.xml") != null)
           return true;
       
       //not a webapp
       return false;
    }

    /**
     * A bundle has been added that could be a webapp
     *
     * @param bundle the bundle
     */
    @Override
    public boolean bundleAdded(Bundle bundle) throws Exception
    {
        if (bundle == null)
            return false;
        
        //can this bundle be deployed to my environment?
        if (!isDeployable(bundle))
            return false;

        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader((ClassLoader)getServer().getAttribute(OSGiServerConstants.SERVER_CLASSLOADER));
        try
        {
            @SuppressWarnings("unchecked")
            Dictionary<String, String> headers = bundle.getHeaders();
            
            //does the bundle have a OSGiWebappConstants.JETTY_WAR_FOLDER_PATH 
            String staticResourcesLocation = Util.getManifestHeaderValue(OSGiWebappConstants.JETTY_WAR_RESOURCE_PATH, headers);
            if (staticResourcesLocation != null)
            {
                //TODO : we don't know whether an app is actually deployed, as deploymentManager swallows all
                //exceptions inside the impl of addApp. Need to send the Event and also register as a service
                //only if the deployment succeeded
                OSGiApp app = new OSGiApp(getDeploymentManager(), this, bundle);
                app.setBaseResource(staticResourcesLocation);
                _bundleMap.put(bundle, app);
                getDeploymentManager().addApp(app);
                return true;
            }

            //does the bundle have a WEB-INF/web.xml
            if (bundle.getEntry("/WEB-INF/web.xml") != null)
            {
                String base = ".";
                OSGiApp app = new OSGiApp(getDeploymentManager(), this, bundle);
                app.setBaseResource(base);
                _bundleMap.put(bundle, app);
                getDeploymentManager().addApp(app);
                return true;
            }

            //does the bundle define a OSGiWebappConstants.RFC66_WEB_CONTEXTPATH
            if (headers.get(OSGiWebappConstants.RFC66_WEB_CONTEXTPATH) != null)
            {
                //Could be a static webapp with no web.xml
                String base = ".";
                OSGiApp app = new OSGiApp(getDeploymentManager(), this, bundle);
                app.setBaseResource(base);
                _bundleMap.put(bundle, app);
                getDeploymentManager().addApp(app);
                return true;
            }

            //not a webapp
            return false;
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(cl);
        }
    }

    /**
     * Bundle has been removed. If it was a webapp we deployed, undeploy it.
     *
     * @param bundle the bundle
     * @return true if this was a webapp we had deployed, false otherwise
     */
    @Override
    public boolean bundleRemoved(Bundle bundle) throws Exception
    {
        App app = _bundleMap.remove(bundle);
        if (app != null)
        {
            getDeploymentManager().removeApp(app);
            return true;
        }
        return false;
    }
}
