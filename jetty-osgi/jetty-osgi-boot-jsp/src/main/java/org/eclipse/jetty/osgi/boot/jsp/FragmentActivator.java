//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.osgi.boot.jsp;

import org.eclipse.jetty.osgi.boot.internal.serverfactory.ServerInstanceWrapper;
import org.eclipse.jetty.osgi.boot.jasper.ContainerTldBundleDiscoverer;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

/**
 * FragmentActivator
 *
 * Sets up support for jsp and jstl. All relevant jsp jars must also be installed
 * into the osgi environment.
 * <p>
 * Note that as this is part of a bundle fragment, this activator is NOT
 * called by the OSGi environment. Instead, the org.eclipse.jetty.osgi.boot.utils.internal.PackageAdminTracker
 * simulates fragment activation and causes this class's start() method to
 * be called.
 * </p>
 * <p>
 * The package of this class MUST match the Bundle-SymbolicName of this fragment
 * in order for the PackageAdminTracker to find it.
 * </p>
 */
public class FragmentActivator implements BundleActivator
{
    /**
     *
     */
    @Override
    public void start(BundleContext context) throws Exception
    {
        //set up some classes that will look for bundles with tlds that must be converted
        //to urls and treated as if they are on the Jetty container's classpath so that 
        //jasper can deal with them
        ServerInstanceWrapper.addContainerTldBundleDiscoverer(new ContainerTldBundleDiscoverer());
    }

    /**
     *
     */
    @Override
    public void stop(BundleContext context) throws Exception
    {

    }
}
