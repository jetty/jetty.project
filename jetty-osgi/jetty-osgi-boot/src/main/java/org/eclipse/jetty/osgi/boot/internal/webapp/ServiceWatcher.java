//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.osgi.boot.internal.webapp;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

import org.eclipse.jetty.osgi.boot.JettyBootstrapActivator;
import org.eclipse.jetty.osgi.boot.OSGiServerConstants;
import org.eclipse.jetty.osgi.boot.OSGiWebappConstants;
import org.eclipse.jetty.osgi.boot.ServiceProvider;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;

/**
 * ServiceWatcher
 * 
 * When a {@link ContextHandler} is activated as an osgi service we find a jetty deployer
 * for it. The ContextHandler could be either a WebAppContext or any other derivative of 
 * ContextHandler.
 * 
 * ContextHandlers and WebApps can also be deployed into jetty without creating them as
 * osgi services. Instead, they can be deployed via manifest headers inside bundles. See
 * {@link BundleWatcher}.
 */
public class ServiceWatcher implements ServiceTrackerCustomizer
{
    private static Logger LOG = Log.getLogger(ServiceWatcher.class);
    
    public static final String FILTER = "(objectclass=" + ServiceProvider.class.getName() + ")";

    
    //track all instances of deployers of webapps as bundles       
    ServiceTracker _serviceTracker;
    
    
     
    /* ------------------------------------------------------------ */
    /**
     * @param registry
     */
    public ServiceWatcher() throws Exception
    {
        //track all instances of deployers of webapps
        Bundle myBundle = FrameworkUtil.getBundle(this.getClass());
        _serviceTracker = new ServiceTracker(myBundle.getBundleContext(), FrameworkUtil.createFilter(FILTER),null);
        _serviceTracker.open();
    }


   
    /* ------------------------------------------------------------ */
    /**
     * @param managedServerName
     * @return
     */
    public Map<ServiceReference, ServiceProvider> getDeployers(String managedServerName)
    {
        if (managedServerName == null)
            managedServerName = OSGiServerConstants.MANAGED_JETTY_SERVER_DEFAULT_NAME;
        
        Map<ServiceReference, ServiceProvider> candidates = new HashMap<ServiceReference, ServiceProvider>();
        
        ServiceReference[] references = _serviceTracker.getServiceReferences();
        if (references != null)
        {
            for (ServiceReference ref:references)
            {
                String name = (String)ref.getProperty(OSGiServerConstants.MANAGED_JETTY_SERVER_NAME);
                if (managedServerName.equalsIgnoreCase(name))
                {
                    ServiceProvider candidate = (ServiceProvider)_serviceTracker.getService(ref);
                    if (candidate != null)
                        candidates.put(ref, candidate);
                }
            }
        }
       return candidates;
    }

    


    /* ------------------------------------------------------------ */
    /** 
     * A Service that is a ContextHandler is detected.
     * @see org.osgi.util.tracker.ServiceTrackerCustomizer#addingService(org.osgi.framework.ServiceReference)
     */
    @Override
    public Object addingService(ServiceReference reference)
    {
        BundleContext context = FrameworkUtil.getBundle(JettyBootstrapActivator.class).getBundleContext();
        ContextHandler contextHandler = (ContextHandler) context.getService(reference); 
        return addService(context, contextHandler, reference);
    }


    /* ------------------------------------------------------------ */
    /** 
     * A Service that is a ContextHandler has been modified. We
     * undeploy and then redeploy the ContextHandler.
     * 
     * @see org.osgi.util.tracker.ServiceTrackerCustomizer#modifiedService(org.osgi.framework.ServiceReference, java.lang.Object)
     */
    @Override
    public void modifiedService(ServiceReference reference, Object service)
    {
        BundleContext context = FrameworkUtil.getBundle(JettyBootstrapActivator.class).getBundleContext();
        ContextHandler contextHandler = (ContextHandler) context.getService(reference);
        removeService (context, contextHandler, reference);
        addService (context, contextHandler, reference);
    }


    /* ------------------------------------------------------------ */
    /** 
     * A Service that is a ContextHandler is being removed.
     * @see org.osgi.util.tracker.ServiceTrackerCustomizer#removedService(org.osgi.framework.ServiceReference, java.lang.Object)
     */
    @Override
    public void removedService(ServiceReference reference, Object service)
    {
        BundleContext context = FrameworkUtil.getBundle(JettyBootstrapActivator.class).getBundleContext();
        ContextHandler contextHandler = (ContextHandler) context.getService(reference); 
       removeService (context, contextHandler, reference);
    }
    
    
    
    /* ------------------------------------------------------------ */
    /** Deploy ContextHandler that is a Service.
     * 
     * @param reference
     * @return
     */
    public Object addService (BundleContext context, ContextHandler contextHandler, ServiceReference reference)
    {
        if (contextHandler.getServer() != null)
        {
            // is configured elsewhere.
            return context.getService(reference);
        }
        String watermark = (String)reference.getProperty(OSGiWebappConstants.WATERMARK);
        if (watermark != null && !"".equals(watermark))
            return context.getService(reference); //one of our deployers just registered the context as an OSGi service, so we can ignore it
        
        //Get a jetty deployer targetted to the named server instance, or the default one if not named
        String serverName = (String)reference.getProperty(OSGiServerConstants.MANAGED_JETTY_SERVER_NAME);    
        Map<ServiceReference, ServiceProvider> candidates = getDeployers(serverName);
        if (candidates != null)
        {
            boolean added = false;
            Iterator<Entry<ServiceReference, ServiceProvider>> itor = candidates.entrySet().iterator();
            while (!added && itor.hasNext())
            {
                Entry<ServiceReference, ServiceProvider> e = itor.next();
                try
                {
                    added = e.getValue().serviceAdded(reference, contextHandler);
                    if (added && LOG.isDebugEnabled())
                        LOG.debug("Provider "+e.getValue()+" deployed "+contextHandler);
                }
                catch (Exception x)
                {
                    LOG.warn("Error deploying service representing jetty context", x);
                }
            }
        }
        return context.getService(reference);
    }
    
    
    
    
    /* ------------------------------------------------------------ */
    /**
     * Undeploy a ContextHandler that is a Service.
     * 
     * @param reference
     */
    public void removeService (BundleContext context, ContextHandler contextHandler, ServiceReference reference)
    {
        //Get a jetty deployer targetted to the named server instance, or the default one if not named
        //The individual deployer  will decide if it can remove the context or not
        String serverName = (String)reference.getProperty(OSGiServerConstants.MANAGED_JETTY_SERVER_NAME);    
        Map<ServiceReference, ServiceProvider> candidates = getDeployers(serverName);
        if (candidates != null)
        {
            boolean removed = false;
            Iterator<Entry<ServiceReference, ServiceProvider>> itor = candidates.entrySet().iterator();
            while (!removed && itor.hasNext())
            {
                Entry<ServiceReference, ServiceProvider> e = itor.next();
                try
                {
                    removed = e.getValue().serviceRemoved(reference, contextHandler);
                }
                catch (Exception x)
                {
                    LOG.warn("Error undeploying service representing jetty context ", x);
                }
            }
        }
    }
}
