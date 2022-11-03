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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.eclipse.jetty.ee9.osgi.boot.OSGiMetaInfConfiguration;
import org.eclipse.jetty.osgi.util.ServerClasspathContributor;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

/**
 * @author janb
 *
 */
public class TLDServerClasspathContributor implements ServerClasspathContributor
{
    
    /**
     * Name of a class that belongs to the jstl bundle. From that class
     * we locate the corresponding bundle.
     */
    private static String JSTL_BUNDLE_CLASS = "org.apache.taglibs.standard.tag.el.core.WhenTag";

    @Override
    public List<Bundle> getScannableBundles()
    {
        if (!isJspAvailable())
        {
            return Collections.emptyList();
        }

        List<Bundle> scannableBundles = new ArrayList<>();
        List<String> bundleNames = Collections.emptyList();

        String tmp = System.getProperty(OSGiMetaInfConfiguration.SYS_PROP_TLD_BUNDLES); //comma separated exact names

        if (tmp != null)
        {
            String[] names = tmp.split(", \n\r\t");
            bundleNames = Arrays.asList(names);
        }

        Bundle jstlBundle = findJstlBundle();
        if (jstlBundle != null)
            scannableBundles.add(jstlBundle);
        
        final Bundle[] bundles = FrameworkUtil.getBundle(getClass()).getBundleContext().getBundles();
        for (Bundle bundle : bundles)
        {
            if (bundleNames.contains(bundle.getSymbolicName()))
                scannableBundles.add(bundle);
        }
      
        return scannableBundles;
    }
    
    /**
     * Check that jsp is on the classpath
     *
     * @return <code>true</code> if jsp is available in the environment
     */
    public boolean isJspAvailable()
    {
        try
        {
            getClass().getClassLoader().loadClass("org.apache.jasper.servlet.JspServlet");
            return true;
        }
        catch (Exception e)
        {
            return false;
        }
    }

    /**
     * Find the bundle that contains a jstl implementation class, which assumes that
     * the jstl taglibs will be inside the same bundle.
     *
     * @return Bundle contains the jstl implementation class
     */
    public Bundle findJstlBundle()
    {
        Class<?> jstlClass = null;

        try
        {
            jstlClass = getClass().getClassLoader().loadClass(JSTL_BUNDLE_CLASS);
            return FrameworkUtil.getBundle(jstlClass);
        }
        catch (ClassNotFoundException e)
        {
            //no jstl do nothing
        }

        return null;
    }

}
