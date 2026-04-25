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

package org.eclipse.jetty.ee.osgi.boot.jsp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.eclipse.jetty.ee.osgi.boot.OSGiMetaInfConfiguration;
import org.eclipse.jetty.osgi.ServerClasspathContributor;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

/**
 * Represents the set of bundles that contain jstl tag libs that must be on the
 * equivalent of jetty's classpath.
 */
public class TLDServerClasspathContributor implements ServerClasspathContributor
{
    /**
     * Names of classes that belong to jstl bundles. From that class
     * we locate the corresponding bundle. Wasp is used for EE11,
     * Glassfish JSTL is used for EE10.
     */
    private static final String[] JSTL_BUNDLE_CLASSES = {
        "org.glassfish.wasp.taglibs.standard.tag.el.core.WhenTag",  // Wasp (EE11)
        "org.apache.taglibs.standard.tag.el.core.WhenTag"            // Glassfish JSTL (EE10)
    };

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
<<<<<<< HEAD
        for (String jstlBundleClass : JSTL_BUNDLE_CLASSES)
        {
            try
            {
                Class<?> jstlClass = getClass().getClassLoader().loadClass(jstlBundleClass);
                return FrameworkUtil.getBundle(jstlClass);
            }
            catch (ClassNotFoundException e)
            {
                //try next
            }
        }

=======
        for (String className : JSTL_BUNDLE_CLASSES)
        {
            try
            {
                Class<?> jstlClass = getClass().getClassLoader().loadClass(className);
                return FrameworkUtil.getBundle(jstlClass);
            }
            catch (ClassNotFoundException ignored)
            {
                //try next class
            }
        }
>>>>>>> 0fbc7fd28e8 (way more fixes even for distribution tests)
        return null;
    }
}
