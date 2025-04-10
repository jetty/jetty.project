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

import java.util.HashMap;
import java.util.Map;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceReference;
import org.osgi.service.packageadmin.PackageAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PackageAdminServiceTracker
 * <p>
 * When the PackageAdmin service is activated we can look for the fragments
 * attached to this bundle and do a fake "activate" on them.
 * <p>
 * See particularly the jetty-eeX-osgi-boot-jsp fragment bundles that uses this facility.
 */
@SuppressWarnings("deprecation")
public class PackageAdminServiceListener implements ServiceListener
{
    private static final Logger LOG = LoggerFactory.getLogger(PackageAdminServiceListener.class);

    private final BundleContext _bootBundleContext;
    private final Map<String, BundleActivator> _activatedFragments = new HashMap<>();
    private final BundleActivator _bootBundleActivator;

    private PackageAdmin _packageAdmin;

    public PackageAdminServiceListener(BundleActivator activator, BundleContext context)
        throws Exception
    {
        _bootBundleActivator = activator;
        _bootBundleContext = context;
        ServiceReference<?> sr = _bootBundleContext.getServiceReference(PackageAdmin.class.getName());
        if (sr != null)
            _packageAdmin = (PackageAdmin)_bootBundleContext.getService(sr);

        invokeFragmentActivators();
        _bootBundleContext.addServiceListener(this, "(objectclass=" + PackageAdmin.class.getName() + ")");
    }

    /**
     * Invokes the optional BundleActivator in each fragment. By convention the
     * bundle activator for a fragment must be in the package that is defined by
     * the symbolic name of the fragment and the name of the class must be
     * 'FragmentActivator'.
     *
     * @param event The <code>ServiceEvent</code> object.
     */
    @Override
    public void serviceChanged(ServiceEvent event)
    {
        if (event.getType() == ServiceEvent.REGISTERED)
        {
            try
            {
                invokeFragmentActivators();
            }
            catch (Exception e)
            {
                LOG.warn("Error invoking fragment activators", e);
            }
        }
    }

    /**
     * Invoke a fake Activator for a fragment bundle that is associated with this bundle.
     */
    private void invokeFragmentActivators()
    {
        Bundle[] fragments = _packageAdmin.getFragments(_bootBundleContext.getBundle());
        if (fragments == null)
        {
            return;
        }
        // for each fragment, call a fake activator. The fake activator will have
        // a classname that matches the pattern symbolicname.FragmentActivator
        for (Bundle frag : fragments)
        {
            try
            {
                if (!_activatedFragments.containsKey(frag.getSymbolicName()))
                {
                    String fragmentActivator = frag.getSymbolicName() + ".FragmentActivator";
                    Class<?> c = Class.forName(fragmentActivator);
                    BundleActivator bActivator = (BundleActivator)c.getDeclaredConstructor().newInstance();
                    _activatedFragments.put(frag.getSymbolicName(), bActivator);
                    bActivator.start(_bootBundleContext);

                    // if the activator has bundles to contribute to the server classpath register them
                    if (bActivator instanceof ServerClasspathContributor.Source source && _bootBundleActivator instanceof ServerClasspathContributor.Registry registry)
                        source.registerServerClasspathContributors(registry);
                }
            }
            catch (ClassNotFoundException e)
            {
                //Fragments do not necessarily have FragmentActivators
                e.printStackTrace();
            }
            catch (Exception e)
            {
                LOG.info("Unable to start fragment: {}", frag, e);
            }
        }
    }

    public void stop()
    {
        for (Map.Entry<String, BundleActivator> fragmentActivator : _activatedFragments.entrySet())
        {
            try
            {
                if (fragmentActivator instanceof ServerClasspathContributor.Source source && _bootBundleActivator instanceof ServerClasspathContributor.Registry registry)
                    source.unregisterServerClasspathContributors(registry);
                fragmentActivator.getValue().stop(_bootBundleContext);
            }
            catch (Exception e)
            {
                LOG.warn("Unable to stop fragment {}", fragmentActivator.getKey(), e);
            }
        }
        _activatedFragments.clear();
        _bootBundleContext.removeServiceListener(this);
    }
}

