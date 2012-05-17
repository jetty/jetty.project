// ========================================================================
// Copyright (c) 2009 Intalio, Inc.
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
package org.eclipse.jetty.osgi.boot.internal.webapp;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.eclipse.jetty.osgi.boot.BundleWebAppProvider;
import org.eclipse.jetty.osgi.boot.JettyBootstrapActivator;
import org.eclipse.jetty.osgi.boot.OSGiServerConstants;
import org.eclipse.jetty.osgi.boot.OSGiWebappConstants;
import org.eclipse.jetty.osgi.boot.ServiceProvider;
import org.eclipse.jetty.osgi.boot.internal.serverfactory.DefaultJettyAtJettyHomeHelper;
import org.eclipse.jetty.osgi.boot.internal.serverfactory.IManagedJettyServerRegistry;
import org.eclipse.jetty.osgi.boot.internal.serverfactory.ServerInstanceWrapper;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.Scanner;
import org.eclipse.jetty.webapp.WebAppContext;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceReference;
import org.osgi.util.tracker.ServiceTracker;

/**
 * JettyContextHandlerServiceTracker
 * 
 * When a {@link ContextHandler} is activated as an osgi service we find a jetty deployer
 * for it. The ContextHandler could be either a WebAppContext or any other derivative of 
 * ContextHandler.
 * 
 * ContextHandlers and WebApps can also be deployed into jetty without creating them as
 * osgi services. Instead, they can be deployed via manifest headers inside bundles. See
 * {@link WebBundleTrackerCustomizer}.
 */
public class JettyContextHandlerServiceTracker implements ServiceListener
{
    private static Logger LOG = Log.getLogger(JettyContextHandlerServiceTracker.class);
    
    public static final String FILTER = "(objectclass=" + ServiceProvider.class.getName() + ")";

    
    //track all instances of deployers of webapps as bundles       
    ServiceTracker _serviceTracker;
    
    
     
    /* ------------------------------------------------------------ */
    /**
     * @param registry
     */
    public JettyContextHandlerServiceTracker() throws Exception
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
     * Receives notification that a service has had a lifecycle change.
     * 
     * @param ev The <code>ServiceEvent</code> object.
     */
    /** 
     * @see org.osgi.framework.ServiceListener#serviceChanged(org.osgi.framework.ServiceEvent)
     */
    public void serviceChanged(ServiceEvent ev)
    {
        ServiceReference sr = ev.getServiceReference();
        switch (ev.getType())
        {
            case ServiceEvent.MODIFIED:
            case ServiceEvent.UNREGISTERING:
            {
                BundleContext context = FrameworkUtil.getBundle(JettyBootstrapActivator.class).getBundleContext();
                ContextHandler contextHandler = (ContextHandler) context.getService(sr);
              
                
                //Get a jetty deployer targetted to the named server instance, or the default one if not named
                String serverName = (String)sr.getProperty(OSGiServerConstants.MANAGED_JETTY_SERVER_NAME);    
                Map<ServiceReference, ServiceProvider> candidates = getDeployers(serverName);
                if (candidates != null)
                {
                    boolean removed = false;
                    Iterator<Entry<ServiceReference, ServiceProvider>> itor = candidates.entrySet().iterator();
                    while (!removed && itor.hasNext())
                    {
                        Entry<ServiceReference, ServiceProvider> e = itor.next();
                        removed = e.getValue().serviceRemoved(sr, contextHandler);
                    }
                }
            }
            if (ev.getType() == ServiceEvent.UNREGISTERING)
            {
                break;
            }
            else
            {
                // modified, meaning: we reload it. now that we stopped it;
                // we can register it.
            }
            case ServiceEvent.REGISTERED:
            {
                Bundle contributor = sr.getBundle();
                BundleContext context = FrameworkUtil.getBundle(JettyBootstrapActivator.class).getBundleContext();
                ContextHandler contextHandler = (ContextHandler) context.getService(sr);
                if (contextHandler.getServer() != null)
                {
                    // is configured elsewhere.
                    return;
                }
                
                //Get a jetty deployer targetted to the named server instance, or the default one if not named
                String serverName = (String)sr.getProperty(OSGiServerConstants.MANAGED_JETTY_SERVER_NAME);    
                Map<ServiceReference, ServiceProvider> candidates = getDeployers(serverName);
                if (candidates != null)
                {
                    boolean added = false;
                    Iterator<Entry<ServiceReference, ServiceProvider>> itor = candidates.entrySet().iterator();
                    while (!added && itor.hasNext())
                    {
                        Entry<ServiceReference, ServiceProvider> e = itor.next();
                        added = e.getValue().serviceAdded(sr, contextHandler);
                        if (added && LOG.isDebugEnabled())
                            LOG.debug("Provider "+e.getValue()+" deployed "+contextHandler);
                    }
                }
                break;
            }
        }
    }
}
