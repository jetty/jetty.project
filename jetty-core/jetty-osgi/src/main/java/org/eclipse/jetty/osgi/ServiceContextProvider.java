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
import org.eclipse.jetty.deploy.AppProvider;
import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.ee9.boot.ServerInstanceWrapper;
import org.eclipse.jetty.ee9.boot.util.Util;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.Filter;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.util.tracker.ServiceTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ServiceContextProvider
 *
 * Jetty DeploymentManager Provider that is able to deploy ContextHandlers discovered via OSGi as services.
 */
public class ServiceContextProvider extends AbstractContextProvider implements ServiceProvider
{
    private static final Logger LOG = LoggerFactory.getLogger(AbstractContextProvider.class);

    private Map<ServiceReference, App> _serviceMap = new HashMap<>();

    private ServiceRegistration _serviceRegForServices;

    ServiceTracker _tracker;

    /**
     * ContextTracker
     */
    public class ContextTracker extends ServiceTracker
    {

        public ContextTracker(BundleContext bundleContext, Filter filter)
        {
            super(bundleContext, filter, null);
        }

        @Override
        public Object addingService(ServiceReference reference)
        {
            ContextHandler h = (ContextHandler)context.getService(reference);
            serviceAdded(reference, h);
            return h;
        }

        @Override
        public void modifiedService(ServiceReference reference, Object service)
        {
            removedService(reference, service);
            addingService(reference);
        }

        @Override
        public void removedService(ServiceReference reference, Object service)
        {
            context.ungetService(reference);
            serviceRemoved(reference, (ContextHandler)service);
        }
    }

    /**
     * ServiceApp
     */
    public class ServiceApp extends OSGiApp
    {
        public ServiceApp(DeploymentManager manager, AppProvider provider, Bundle bundle, Dictionary properties, String contextFile, String originId)
        {
            super(manager, provider, bundle, properties, contextFile, originId);
        }

        public ServiceApp(DeploymentManager manager, AppProvider provider, String originId, Bundle bundle, String contextFile)
        {
            super(manager, provider, originId, bundle, contextFile);
        }

        @Override
        public void registerAsOSGiService() throws Exception
        {
            //not applicable for apps that are already services
        }

        @Override
        protected void deregisterAsOSGiService() throws Exception
        {
            //not applicable for apps that are already services
        }
    }

    public ServiceContextProvider(ServerInstanceWrapper wrapper)
    {
        super(wrapper);
    }

    @Override
    public boolean serviceAdded(ServiceReference serviceRef, ContextHandler context)
    {
        if (context == null || serviceRef == null)
            return false;

        if (context instanceof org.eclipse.jetty.ee9.webapp.WebAppContext)
            return false; //the ServiceWebAppProvider will deploy it

        String watermark = (String)serviceRef.getProperty(OSGiWebappConstants.WATERMARK);
        if (watermark != null && !"".equals(watermark))
            return false;  //this service represents a contexthandler that has already been registered as a service by another of our deployers

        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(getServer().getParentClassLoaderForWebapps());
        try
        {
            //See if there is a context file to apply to this pre-made context
            String contextFile = (String)serviceRef.getProperty(OSGiWebappConstants.JETTY_CONTEXT_FILE_PATH);

            String[] keys = serviceRef.getPropertyKeys();
            Dictionary<String, Object> properties = new Hashtable<>();
            if (keys != null)
            {
                for (String key : keys)
                {
                    properties.put(key, serviceRef.getProperty(key));
                }
            }
            Bundle bundle = serviceRef.getBundle();
            String originId = bundle.getSymbolicName() + "-" + bundle.getVersion().toString() + "-" + (contextFile != null ? contextFile : serviceRef.getProperty(Constants.SERVICE_ID));
            ServiceApp app = new ServiceApp(getDeploymentManager(), this, bundle, properties, contextFile, originId);
            app.setHandler(context); //set the pre=made ContextHandler instance
            _serviceMap.put(serviceRef, app);
            getDeploymentManager().addApp(app);
            return true;
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(cl);
        }
    }

    @Override
    public boolean serviceRemoved(ServiceReference serviceRef, ContextHandler context)
    {

        if (context == null || serviceRef == null)
            return false;

        String watermark = (String)serviceRef.getProperty(OSGiWebappConstants.WATERMARK);
        if (watermark != null && !"".equals(watermark))
            return false;  //this service represents a contexthandler that will be deregistered as a service by another of our deployers

        App app = _serviceMap.remove(serviceRef);
        if (app != null)
        {
            getDeploymentManager().removeApp(app);
            return true;
        }

        return false;
    }

    @Override
    protected void doStart() throws Exception
    {

        BundleContext bundleContext = FrameworkUtil.getBundle(this.getClass()).getBundleContext();

        //Start a tracker to find webapps that are osgi services that are targeted to my server name
        _tracker = new ContextTracker(bundleContext,
            Util.createFilter(bundleContext, ContextHandler.class.getName(), getServer().getManagedServerName()));
        _tracker.open();

        //register as an osgi service for deploying contexts defined in a bundle, advertising the name of the jetty Server instance we are related to
        Dictionary<String, String> properties = new Hashtable<>();
        properties.put(OSGiServerConstants.MANAGED_JETTY_SERVER_NAME, getServer().getManagedServerName());

        //register as an osgi service for deploying contexts, advertising the name of the jetty Server instance we are related to
        _serviceRegForServices = FrameworkUtil.getBundle(this.getClass()).getBundleContext().registerService(ServiceProvider.class.getName(), this, properties);
        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        if (_tracker != null)
            _tracker.close();

        //unregister ourselves 
        if (_serviceRegForServices != null)
        {
            try
            {
                _serviceRegForServices.unregister();
            }
            catch (Exception e)
            {
                LOG.warn("Unable to unregister {}", _serviceRegForServices, e);
            }
        }
        super.doStop();
    }
}
