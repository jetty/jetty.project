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

package org.eclipse.jetty.ee9.osgi.boot;

import java.util.Dictionary;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;

import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.deploy.AppProvider;
import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.ee9.osgi.boot.internal.serverfactory.ServerInstanceWrapper;
import org.eclipse.jetty.ee9.osgi.boot.utils.Util;
import org.eclipse.jetty.ee9.webapp.WebAppContext;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Filter;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.util.tracker.ServiceTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ServiceWebAppProvider
 * <p>
 * Jetty Provider that knows how to deploy a WebApp that has been registered as an OSGi service.
 */
public class ServiceWebAppProvider extends AbstractWebAppProvider implements ServiceProvider
{
    private static final Logger LOG = LoggerFactory.getLogger(AbstractWebAppProvider.class);

    /**
     * Map of ServiceRef to App. Used when it is an osgi service that is a WebAppContext.
     */
    private Map<ServiceReference, App> _serviceMap = new HashMap<>();

    private ServiceRegistration _serviceRegForServices;

    private ServiceTracker webappTracker;

    /**
     * WebAppTracker
     */
    public class WebAppTracker extends ServiceTracker
    {
        /**
         * @param bundleContext the osgi context
         * @param filter the osgi filter for the tracker
         */
        public WebAppTracker(BundleContext bundleContext, Filter filter)
        {
            super(bundleContext, filter, null);
        }

        @Override
        public Object addingService(ServiceReference reference)
        {
            WebAppContext wac = (WebAppContext)context.getService(reference);
            serviceAdded(reference, wac);
            return wac;
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
            serviceRemoved(reference, (WebAppContext)service);
            context.ungetService(reference);
        }
    }

    /**
     * ServiceApp
     */
    public class ServiceApp extends OSGiApp
    {

        public ServiceApp(DeploymentManager manager, AppProvider provider, Bundle bundle, Dictionary<String, String> properties, String originId)
        {
            super(manager, provider, bundle, properties, originId);
        }

        public ServiceApp(DeploymentManager manager, AppProvider provider, Bundle bundle, String originId)
        {
            super(manager, provider, bundle, originId);
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

    public ServiceWebAppProvider(ServerInstanceWrapper wrapper)
    {
        super(wrapper);
    }

    /**
     * A webapp that was deployed as an osgi service has been added,
     * and we want to deploy it.
     *
     * @param context the webapp
     */
    @Override
    public boolean serviceAdded(ServiceReference serviceRef, ContextHandler context)
    {
        if (context == null || !(context instanceof WebAppContext))
            return false;

        String watermark = (String)serviceRef.getProperty(OSGiWebappConstants.WATERMARK);
        if (watermark != null && !"".equals(watermark))
            return false;  //this service represents a webapp that has already been registered as a service by another of our deployers

        WebAppContext webApp = (WebAppContext)context;
        Dictionary<String, String> properties = new Hashtable<>();

        String contextPath = (String)serviceRef.getProperty(OSGiWebappConstants.RFC66_WEB_CONTEXTPATH);
        if (contextPath == null)
            return false; //No context path

        String base = (String)serviceRef.getProperty(OSGiWebappConstants.JETTY_WAR_RESOURCE_PATH);

        if (base == null)
            return false; //No webapp base

        String webdefaultXml = (String)serviceRef.getProperty(OSGiWebappConstants.JETTY_DEFAULT_WEB_XML_PATH);
        if (webdefaultXml != null)
            properties.put(OSGiWebappConstants.JETTY_DEFAULT_WEB_XML_PATH, webdefaultXml);

        String webXml = (String)serviceRef.getProperty(OSGiWebappConstants.JETTY_WEB_XML_PATH);
        if (webXml != null)
            properties.put(OSGiWebappConstants.JETTY_WEB_XML_PATH, webXml);

        String extraClassPath = (String)serviceRef.getProperty(OSGiWebappConstants.JETTY_EXTRA_CLASSPATH);
        if (extraClassPath != null)
            properties.put(OSGiWebappConstants.JETTY_EXTRA_CLASSPATH, extraClassPath);

        String bundleInstallOverride = (String)serviceRef.getProperty(OSGiWebappConstants.JETTY_BUNDLE_INSTALL_LOCATION_OVERRIDE);
        if (bundleInstallOverride != null)
            properties.put(OSGiWebappConstants.JETTY_BUNDLE_INSTALL_LOCATION_OVERRIDE, bundleInstallOverride);

        String requiredTlds = (String)serviceRef.getProperty(OSGiWebappConstants.REQUIRE_TLD_BUNDLE);
        if (requiredTlds == null)
            requiredTlds = (String)serviceRef.getProperty(OSGiWebappConstants.SERVICE_PROP_REQUIRE_TLD_BUNDLE);
        if (requiredTlds != null)
            properties.put(OSGiWebappConstants.REQUIRE_TLD_BUNDLE, requiredTlds);

        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(getServer().getParentClassLoaderForWebapps());
        try
        {
            String originId = getOriginId(serviceRef.getBundle(), base);
            ServiceApp app = new ServiceApp(getDeploymentManager(), this, serviceRef.getBundle(), properties, originId);
            app.setContextPath(contextPath);
            app.setWebAppPath(base);
            app.setWebAppContext(webApp); //set the pre=made webapp instance
            _serviceMap.put(serviceRef, app);
            getDeploymentManager().addApp(app);
            return true;
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(cl);
        }
    }

    /**
     * @param context the webapp
     */
    @Override
    public boolean serviceRemoved(ServiceReference serviceRef, ContextHandler context)
    {
        if (context == null || !(context instanceof WebAppContext))
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
        webappTracker = new WebAppTracker(bundleContext,
            Util.createFilter(bundleContext, WebAppContext.class.getName(), getServer().getManagedServerName()));
        webappTracker.open();

        //register as an osgi service for deploying bundles, advertising the name of the jetty Server instance we are related to
        Dictionary<String, String> properties = new Hashtable<>();
        properties.put(OSGiServerConstants.MANAGED_JETTY_SERVER_NAME, getServer().getManagedServerName());

        //register as an osgi service for deploying contexts (discovered as osgi services), advertising the name of the jetty Server instance we are related to
        _serviceRegForServices = FrameworkUtil.getBundle(this.getClass()).getBundleContext().registerService(ServiceProvider.class.getName(), this, properties);
        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        webappTracker.close();

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
