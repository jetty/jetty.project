//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.osgi.boot.BundleProvider;
import org.eclipse.jetty.osgi.boot.OSGiServerConstants;
import org.eclipse.jetty.osgi.boot.utils.WebappRegistrationCustomizer;
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
 * WebBundleTrackerCustomizer
 * 
 * 
 * Support bundles that declare a webpp or context directly through headers in their
 * manifest. They will be deployed to the default jetty Server instance.
 * 
 * If you wish to deploy a context or webapp to a different jetty Server instance,
 * register your context/webapp as an osgi service, and set the property OSGiServerConstants.MANAGED_JETTY_SERVER_NAME
 * with the name of the Server instance you wish to depoy to.
 * 
 * @author hmalphettes
 */
public class WebBundleTrackerCustomizer implements BundleTrackerCustomizer
{
    private static final Logger LOG = Log.getLogger(WebBundleTrackerCustomizer.class);
    
    public static Collection<WebappRegistrationCustomizer> JSP_REGISTRATION_HELPERS = new ArrayList<WebappRegistrationCustomizer>();
    public static final String FILTER = "(&(objectclass=" + BundleProvider.class.getName() + ")"+
                                          "("+OSGiServerConstants.MANAGED_JETTY_SERVER_NAME+"="+OSGiServerConstants.MANAGED_JETTY_SERVER_DEFAULT_NAME+"))";

    private ServiceTracker _serviceTracker;
    private BundleTracker _bundleTracker;
    
    /* ------------------------------------------------------------ */
    /**
     * @throws Exception
     */
    public WebBundleTrackerCustomizer ()
    throws Exception
    {
        Bundle myBundle = FrameworkUtil.getBundle(this.getClass());
        
        //track all instances of deployers of webapps/contexts as bundles       
        _serviceTracker = new ServiceTracker(myBundle.getBundleContext(), FrameworkUtil.createFilter(FILTER),null) {
            public Object addingService(ServiceReference reference) {
                Object object = super.addingService(reference);
                LOG.debug("Deployer registered {}", reference);
                openBundleTracker();
                return object;
            }
        };
        _serviceTracker.open();

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
        // nothing the web-bundle was already track. something changed.
        // we only reload the webapps if the bundle is stopped and restarted.
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

    
    /* ------------------------------------------------------------ */
    /**
     * @param bundle
     * @return true if this bundle in indeed a web-bundle.
     */
    private boolean register(Bundle bundle)
    {
        if (bundle == null)
            return false;

        //It might be a bundle that we can deploy to our default jetty server instance
        boolean deployed = false;
        Object[] deployers = _serviceTracker.getServices();
        if (deployers != null)
        {
            int i=0;
            while (!deployed && i<deployers.length)
            {

                BundleProvider p = (BundleProvider)deployers[i];
                try
                {
                    deployed = p.bundleAdded(bundle);
                }
                catch (Exception x)
                {
                    LOG.warn("Error deploying bundle for jetty context", x);
                }
                i++;
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
        Object[] deployers = _serviceTracker.getServices();
        boolean undeployed = false;
        if (deployers != null)
        {
            int i=0;
            while (!undeployed && i<deployers.length)
            {
                try
                {
                    undeployed = ((BundleProvider)deployers[i++]).bundleRemoved(bundle);
                }
                catch (Exception x)
                {
                    LOG.warn("Error undeploying bundle for jetty context", x);
                }
            }
        }
    }

    public void setAndOpenWebBundleTracker(BundleTracker bundleTracker) {
        if(_bundleTracker == null) {
            _bundleTracker = bundleTracker;
            LOG.debug("Bundle tracker is set");
            openBundleTracker();
        }
    }

    private void openBundleTracker() {
        if(_bundleTracker != null && _serviceTracker.getServices() != null &&
                _serviceTracker.getServices().length > 0) {
            _bundleTracker.open();
            LOG.debug("Bundle tracker has been opened");
        }
    }

}
