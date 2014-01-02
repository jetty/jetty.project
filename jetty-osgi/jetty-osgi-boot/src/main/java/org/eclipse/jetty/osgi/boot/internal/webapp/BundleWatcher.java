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

package org.eclipse.jetty.osgi.boot.internal.webapp;


import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

import org.eclipse.jetty.osgi.boot.BundleProvider;
import org.eclipse.jetty.osgi.boot.OSGiServerConstants;
import org.eclipse.jetty.osgi.boot.utils.TldBundleDiscoverer;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;
import org.osgi.util.tracker.BundleTracker;
import org.osgi.util.tracker.BundleTrackerCustomizer;
import org.osgi.util.tracker.ServiceTracker;

/**
 * BundleWatcher
 * 
 * 
 * Tracks the installation and removal of Bundles in the OSGi environment. Any bundles
 * that are added are passed to the set of Jetty DeploymentManager providers to see if
 * the bundle should be deployed as a webapp or ContextHandler into Jetty.
 * 
 * @author hmalphettes
 */
public class BundleWatcher implements BundleTrackerCustomizer
{
    private static final Logger LOG = Log.getLogger(BundleWatcher.class);
    
    public static final Collection<TldBundleDiscoverer> JSP_REGISTRATION_HELPERS = new ArrayList<TldBundleDiscoverer>();


    public static final String FILTER = "(objectclass=" + BundleProvider.class.getName() + ")";
    private ServiceTracker _serviceTracker;
    private BundleTracker _bundleTracker;
    private boolean _waitForDefaultServer = true;
    private boolean _defaultServerReady = false;
    private Bundle _bundle = null;
    
 
    
    /* ------------------------------------------------------------ */
    /**
     * @throws Exception
     */
    public BundleWatcher() throws Exception
    {
        _bundle = FrameworkUtil.getBundle(this.getClass());
        //Track all BundleProviders (Jetty DeploymentManager Providers that can deploy bundles)
        _serviceTracker = new ServiceTracker(_bundle.getBundleContext(), FrameworkUtil.createFilter(FILTER),null);
        _serviceTracker.open();
    }
    
    
    /* ------------------------------------------------------------ */
    public boolean isWaitForDefaultServer()
    {
        return _waitForDefaultServer;
    }


    /* ------------------------------------------------------------ */
    public void setWaitForDefaultServer(boolean waitForDefaultServer)
    {
        _waitForDefaultServer = waitForDefaultServer;
    }

    
    /* ------------------------------------------------------------ */
    public void setBundleTracker (BundleTracker bundleTracker)
    {
        _bundleTracker = bundleTracker;
    }

    
    /* ------------------------------------------------------------ */
    public void open () throws Exception
    {
        if (_waitForDefaultServer && !_defaultServerReady)
        {
            String filter = "(&(objectclass=" + BundleProvider.class.getName() + ")"+
                    "("+OSGiServerConstants.MANAGED_JETTY_SERVER_NAME+"="+OSGiServerConstants.MANAGED_JETTY_SERVER_DEFAULT_NAME+"))";
            
            ServiceTracker defaultServerTracker = new ServiceTracker(_bundle.getBundleContext(), 
                                                                     FrameworkUtil.createFilter(filter),null)
            {
                public Object addingService(ServiceReference reference)
                {
                    try
                    {
                        Object object = super.addingService(reference);
                        LOG.debug("Default Jetty Server registered {}", reference);
                        _defaultServerReady = true;
                        openBundleTracker();
                        return object;
                    }
                    catch (Exception e)
                    {
                        throw new IllegalStateException(e);
                    }
                }
            };
            defaultServerTracker.open();
        }
        else
            openBundleTracker();
    }

    /* ------------------------------------------------------------ */
    /**
     * @param managedServerName
     * @return
     */
    public Map<ServiceReference, BundleProvider> getDeployers(String managedServerName)
    {
        if (managedServerName == null)
            managedServerName = OSGiServerConstants.MANAGED_JETTY_SERVER_DEFAULT_NAME;
        
        Map<ServiceReference, BundleProvider> candidates = new HashMap<ServiceReference, BundleProvider>();
        
        ServiceReference[] references = _serviceTracker.getServiceReferences();
        if (references != null)
        {
            for (ServiceReference ref:references)
            {
                String name = (String)ref.getProperty(OSGiServerConstants.MANAGED_JETTY_SERVER_NAME);                
                if (managedServerName.equalsIgnoreCase(name))
                {
                    BundleProvider candidate = (BundleProvider)_serviceTracker.getService(ref);
                    if (candidate != null)
                        candidates.put(ref, candidate);
                }
            }
        }
       return candidates;
    }

    /* ------------------------------------------------------------ */
    /**
     * A bundle is being added to the <code>BundleTracker</code>.
     * 
     * <p>
     * This method is called before a bundle which matched the search parameters
     * of the <code>BundleTracker</code> is added to the
     * <code>BundleTracker</code>. This method should return the object to be
     * tracked for the specified <code>Bundle</code>. The returned object is
     * stored in the <code>BundleTracker</code> and is available from the
     * {@link BundleTracker#getObject(Bundle) getObject} method.
     * 
     * @param bundle The <code>Bundle</code> being added to the
     *            <code>BundleTracker</code>.
     * @param event The bundle event which caused this customizer method to be
     *            called or <code>null</code> if there is no bundle event
     *            associated with the call to this method.
     * @return The object to be tracked for the specified <code>Bundle</code>
     *         object or <code>null</code> if the specified <code>Bundle</code>
     *         object should not be tracked.
     */
    public Object addingBundle(Bundle bundle, BundleEvent event)
    {
        if (bundle.getState() == Bundle.ACTIVE)
        {
            register(bundle);          
        }
        else if (bundle.getState() == Bundle.STOPPING)
        {
            unregister(bundle);
        }
        else
        {
            // we should not be called in that state as
            // we are registered only for ACTIVE and STOPPING
        }
        return null;
    }

    
    /* ------------------------------------------------------------ */
    /**
     * A bundle tracked by the <code>BundleTracker</code> has been modified.
     * 
     * <p>
     * This method is called when a bundle being tracked by the
     * <code>BundleTracker</code> has had its state modified.
     * 
     * @param bundle The <code>Bundle</code> whose state has been modified.
     * @param event The bundle event which caused this customizer method to be
     *            called or <code>null</code> if there is no bundle event
     *            associated with the call to this method.
     * @param object The tracked object for the specified bundle.
     */
    public void modifiedBundle(Bundle bundle, BundleEvent event, Object object)
    {
        if (bundle.getState() == Bundle.STOPPING || bundle.getState() == Bundle.ACTIVE)
        {
            unregister(bundle);
        }
        if (bundle.getState() == Bundle.ACTIVE)
        {
            register(bundle);
        }
    }

    
    /* ------------------------------------------------------------ */
    /**
     * A bundle tracked by the <code>BundleTracker</code> has been removed.
     * 
     * <p>
     * This method is called after a bundle is no longer being tracked by the
     * <code>BundleTracker</code>.
     * 
     * @param bundle The <code>Bundle</code> that has been removed.
     * @param event The bundle event which caused this customizer method to be
     *            called or <code>null</code> if there is no bundle event
     *            associated with the call to this method.
     * @param object The tracked object for the specified bundle.
     */
    public void removedBundle(Bundle bundle, BundleEvent event, Object object)
    {
        unregister(bundle);
    }

    
    protected void openBundleTracker()
    {
        _bundleTracker.open();
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param bundle
     * @return true if this bundle can be deployed into Jetty
     */
    private boolean register(Bundle bundle)
    {
        if (bundle == null)
            return false;

        //It might be a bundle that is deployable by Jetty.
        //Use any named Server instance provided, defaulting to the default Server instance if none supplied
        boolean deployed = false;
        String serverName = (String)bundle.getHeaders().get(OSGiServerConstants.MANAGED_JETTY_SERVER_NAME);
        Map<ServiceReference, BundleProvider> candidates = getDeployers(serverName);
        if (candidates != null)
        {
            Iterator<Entry<ServiceReference, BundleProvider>> itor = candidates.entrySet().iterator();
            while (!deployed && itor.hasNext())
            {
                Entry<ServiceReference, BundleProvider> e = itor.next();
                try
                {           
                    deployed = e.getValue().bundleAdded(bundle);
                }
                catch (Exception x)
                {
                    LOG.warn("Error deploying bundle for jetty context", x);
                }
            }
        }

        return deployed;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param bundle
     */
    private void unregister(Bundle bundle)
    { 
        boolean undeployed = false;
        String serverName = (String)bundle.getHeaders().get(OSGiServerConstants.MANAGED_JETTY_SERVER_NAME);    
        Map<ServiceReference, BundleProvider> candidates = getDeployers(serverName);
        if (candidates != null)
        {
            Iterator<Entry<ServiceReference, BundleProvider>> itor = candidates.entrySet().iterator();
            while (!undeployed && itor.hasNext())
            {
                Entry<ServiceReference, BundleProvider> e = itor.next();
                try
                {
                    undeployed = e.getValue().bundleRemoved(bundle);
                }
                catch (Exception x)
                {
                    LOG.warn("Error undeploying Bundle representing jetty deployable ", x);
                }
            }
        }
    }
}
