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

package org.eclipse.jetty.osgi.boot.jsp;

import org.eclipse.jetty.osgi.boot.BundleWebAppProvider;
import org.eclipse.jetty.osgi.boot.internal.webapp.WebBundleTrackerCustomizer;
import org.eclipse.jetty.osgi.boot.jasper.PluggableWebAppRegistrationCustomizerImpl;
import org.eclipse.jetty.osgi.boot.jasper.WebappRegistrationCustomizerImpl;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

/**
 * Pseudo fragment activator. Called by the main org.eclipse.jetty.osgi.boot
 * bundle. Please note: this is not a real BundleActivator. Simply something
 * called back by the host bundle.
 * <p>
 * It must be placed in the org.eclipse.jetty.osgi.boot.jsp package: this is
 * because org.eclipse.jetty.osgi.boot.jsp is the symbolic-name of this
 * fragment. From that name, the PackageadminTracker will call this class. IN a
 * different package it won't be called.
 * </p>
 */
public class FragmentActivator implements BundleActivator
{
    /**
     * 
     */
    public void start(BundleContext context) throws Exception
    {
        System.setProperty("org.apache.jasper.compiler.disablejsr199", Boolean.TRUE.toString());
        WebBundleTrackerCustomizer.JSP_REGISTRATION_HELPERS.add(new WebappRegistrationCustomizerImpl());
        WebBundleTrackerCustomizer.JSP_REGISTRATION_HELPERS.add(new PluggableWebAppRegistrationCustomizerImpl());
        //Put in the support for the tag libs
        addTagLibSupport();
      
    }

    /**
     * 
     */
    public void stop(BundleContext context) throws Exception
    {

    }
    
    public void addTagLibSupport ()
    {
        String[] defaultConfigurations = new String[BundleWebAppProvider.getDefaultConfigurations().length+1];
        System.arraycopy(BundleWebAppProvider.getDefaultConfigurations(), 0, defaultConfigurations, 0, BundleWebAppProvider.getDefaultConfigurations().length);
        defaultConfigurations[defaultConfigurations.length-1] = "org.eclipse.jetty.osgi.boot.jsp.TagLibOSGiConfiguration";
        BundleWebAppProvider.setDefaultConfigurations(defaultConfigurations);
    }
}
