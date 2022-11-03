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

package org.eclipse.jetty.ee9.osgi.boot.jsp;

import org.eclipse.jetty.osgi.JettyServerFactory;
import org.eclipse.jetty.osgi.util.ServerClasspathContributor;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

/**
 * FragmentActivator
 *
 * Sets up support for jsp and jstl. All relevant jsp jars must also be installed
 * into the osgi environment.
 * <p>
 * NOTE that as this is part of a bundle fragment, this activator is NOT
 * called by the OSGi environment. Instead, the org.eclipse.jetty.osgi.util.internal.PackageAdminTracker
 * simulates fragment activation and causes this class's start() method to be called.
 * </p>
 * <p>
 * The package of this class MUST match the Bundle-SymbolicName of this fragment
 * in order for the PackageAdminTracker to find it.
 * </p>
 */
public class FragmentActivator implements BundleActivator
{
    ServerClasspathContributor _tldClasspathContributor;
    
    /**
     *
     */
    @Override
    public void start(BundleContext context) throws Exception
    {
        //set up some classes that will look for bundles with tlds that must be converted
        //to urls and treated as if they are on the Jetty container's classpath so that 
        //jasper can deal with them
        _tldClasspathContributor = new TLDServerClasspathContributor();
        JettyServerFactory.registerServerClasspathContributor(_tldClasspathContributor);
    }

    /**
     *
     */
    @Override
    public void stop(BundleContext context) throws Exception
    {
        JettyServerFactory.unregisterServerClasspathContributor(_tldClasspathContributor);
        _tldClasspathContributor = null;
    }
}
