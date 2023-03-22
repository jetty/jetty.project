//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
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
 * BundleContextProvider
 * <p>
 * Handles deploying OSGi bundles that define a context xml file for configuring them.
 */
public class BundleContextProvider extends AbstractContextProvider implements BundleProvider
{
    private static final Logger LOG = LoggerFactory.getLogger(AbstractContextProvider.class);

    private Map<Path, App> _appMap = new HashMap<>();

    private Map<Bundle, List<App>> _bundleMap = new HashMap<>();

    private ServiceRegistration _serviceRegForBundles;

    private BundleTracker _tracker;

    /**
     * ContextBundleTracker
     *
     * Track deployment of Bundles that should be deployed to Jetty as contexts.
     */
    public class ContextBundleTracker extends BundleTracker
    {
        protected String _serverName;

        /**
         * @param bundleContext our bundle
         * @param serverName the Server instance to which we will deploy contexts
         */
        public ContextBundleTracker(BundleContext bundleContext, String serverName)
        {
            super(bundleContext, Bundle.ACTIVE | Bundle.STOPPING, null);
            _serverName = serverName;
        }

        @Override
        public Object addingBundle(Bundle bundle, BundleEvent event)
        {
            try
            {
                String serverName = (String)bundle.getHeaders().get(OSGiServerConstants.MANAGED_JETTY_SERVER_NAME);
                if ((StringUtil.isBlank(serverName) && _serverName.equals(OSGiServerConstants.MANAGED_JETTY_SERVER_DEFAULT_NAME)) ||
                    (!StringUtil.isBlank(serverName) && (serverName.equals(_serverName))))
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

    public BundleContextProvider(String environment, Server server, ContextFactory contextFactory)
    {
        super(environment, server, contextFactory);
    }

    @Override
    protected void doStart() throws Exception
    {
        String serverName = (String)getServer().getAttribute(OSGiServerConstants.MANAGED_JETTY_SERVER_NAME);
        
        //Track bundles that are ContextHandlers that should be deployed
        _tracker = new ContextBundleTracker(FrameworkUtil.getBundle(this.getClass()).getBundleContext(), serverName);
        _tracker.open();

        //register as an osgi service for deploying contexts defined in a bundle, advertising the name of the jetty Server instance we are related to
        Dictionary<String, String> properties = new Hashtable<String, String>();
        properties.put(OSGiServerConstants.MANAGED_JETTY_SERVER_NAME, serverName);
        _serviceRegForBundles = FrameworkUtil.getBundle(this.getClass()).getBundleContext().registerService(BundleProvider.class.getName(), this, properties);
        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        _tracker.close();

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
    }

    /**
     * Deploy a bundle as a Jetty context.
     */
    @Override
    public boolean bundleAdded(Bundle bundle) throws Exception
    {
        if (bundle == null)
            return false;
        
        if (!isDeployable(bundle))
            return false;

        //If the bundle defines a Web-ContextPath then its probably a webapp and the BundleWebAppProvider should deploy it
        if (bundle.getHeaders().get(OSGiWebappConstants.RFC66_WEB_CONTEXTPATH) != null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("BundleContextProvider ignoring bundle {} with {} set", bundle.getSymbolicName(), OSGiWebappConstants.RFC66_WEB_CONTEXTPATH);
            return false;
        }
        
        //comma separated list of context xml files, each of which is a separate ContextHandler to deploy
        String contextFiles = bundle.getHeaders().get(OSGiWebappConstants.JETTY_CONTEXT_FILE_PATH);

        //no contexts to deploy, ignore it
        if (contextFiles == null)
            return false;

        //is the bundle destined for my environment?
        String jettyHome = (String)getServer().getAttribute(OSGiServerConstants.JETTY_HOME);
        Path jettyHomePath = (StringUtil.isBlank(jettyHome) ? null : Paths.get(jettyHome));
        
        boolean added = false;
     
        String[] tmp = contextFiles.split("[,;]");
        for (String contextFile : tmp)
        {            
            OSGiApp app = new OSGiApp(getDeploymentManager(), this, bundle);
            URI contextFilePath = Util.resolvePathAsLocalizedURI(contextFile, app.getBundle(), jettyHomePath);
            
            //set up the single context file for this deployment
            app.getProperties().put(OSGiWebappConstants.JETTY_CONTEXT_FILE_PATH, contextFilePath.toString());
            
            _appMap.put(app.getPath(), app);
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

    /**
     * Bundle has been removed. If it was a context we deployed, undeploy it.
     *
     * @param bundle the bundle
     * @return true if this was a context we had deployed, false otherwise
     */
    @Override
    public boolean bundleRemoved(Bundle bundle) throws Exception
    {
        List<App> apps = _bundleMap.remove(bundle);
        boolean removed = false;
        if (apps != null)
        {
            for (App app : apps)
            {
                if (_appMap.remove(app.getPath()) != null)
                {
                    getDeploymentManager().removeApp(app);
                    removed = true;
                }
            }
        }
        return removed; //true if even 1 context was removed associated with this bundle
    }
}
