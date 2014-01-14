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

package org.eclipse.jetty.osgi.boot.jsp;

import java.io.IOException;
import java.net.URL;
import java.util.Collection;
import java.util.Enumeration;
import java.util.LinkedHashSet;

import org.eclipse.jetty.osgi.boot.OSGiWebappConstants;
import org.eclipse.jetty.osgi.boot.utils.BundleFileLocatorHelperFactory;
import org.eclipse.jetty.osgi.boot.utils.internal.DefaultFileLocatorHelper;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.TagLibConfiguration;
import org.eclipse.jetty.webapp.WebAppContext;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleReference;
import org.osgi.service.packageadmin.PackageAdmin;
import org.osgi.util.tracker.ServiceTracker;

/**
 * <p>
 * Replacement for {@link TagLibConfiguration} for the OSGi integration.
 * </p>
 * <p>
 * In the case of a WAB, tlds can be located in OSGi bundles that are
 * dependencies of the WAB. It is expected that each WAB lists the
 * symbolic-names of the bundles that contain tld files. The list is defined as
 * the value of the header 'Require-TldBundle'
 * </p>
 * <p>
 * Discussions about this are logged in
 * https://bugs.eclipse.org/bugs/show_bug.cgi?id=306971
 * </p>
 */
public class TagLibOSGiConfiguration extends TagLibConfiguration
{
    private static final Logger LOG = Log.getLogger(TagLibOSGiConfiguration.class);

    private ServiceTracker packageAdminServiceTracker = null;

    /**
     * Override the preConfigure; locates the bundles that contain tld files
     * according to the value of the manifest header Require-TldBundle.
     * <p>
     * Set or add to the property TldProcessor.TLDResources the list of located
     * jars so that the super class will scan those.
     * </p>
     */
    public void preConfigure(WebAppContext context) throws Exception
    {
        String requireTldBundle = (String) context.getAttribute(OSGiWebappConstants.REQUIRE_TLD_BUNDLE);
        if (requireTldBundle != null)
        {
            Collection<Resource> resources = getRequireTldBundleAsJettyResources(context, requireTldBundle);
            if (resources != null && !resources.isEmpty())
            {
                Collection<Resource> previouslySet = (Collection<Resource>) context.getAttribute(TagLibConfiguration.TLD_RESOURCES);
                if (previouslySet != null)
                {
                    resources.addAll(previouslySet);
                }
                context.setAttribute(TagLibConfiguration.TLD_RESOURCES, resources);
            }
        }
        super.preConfigure(context);

    }

    /**
     * @param requireTldBundle The comma separated list of bundles' symbolic
     *            names that contain tld for this osgi webapp.
     * @return The collection of jars or folders that match those bundles.
     */
    private Collection<Resource> getRequireTldBundleAsJettyResources(WebAppContext context, String requireTldBundle)
    {
        Bundle bundle = (Bundle) context.getAttribute(OSGiWebappConstants.JETTY_OSGI_BUNDLE);
        PackageAdmin packAdmin = getBundleAdmin();
        String[] symbNames = requireTldBundle.split(", ");
        Collection<Resource> tlds = new LinkedHashSet<Resource>();
        for (String symbName : symbNames)
        {
            Bundle[] bs = packAdmin.getBundles(symbName, null);
            if (bs == null || bs.length == 0) 
            { 
                throw new IllegalArgumentException("Unable to locate the bundle '" + symbName
                                                                                   + "' specified in the "
                                                                                   + OSGiWebappConstants.REQUIRE_TLD_BUNDLE
                                                                                   + " of the manifest of "
                                                                                   + bundle.getSymbolicName()); 
            }
            // take the first one as it is the most recent version?
            Enumeration<URL> en = bs[0].findEntries("META-INF", "*.tld", false);
            boolean atLeastOneTldFound = false;
            while (en.hasMoreElements())
            {
                atLeastOneTldFound = true;
                URL oriUrl = en.nextElement();
                try
                {
                    URL url = BundleFileLocatorHelperFactory.getFactory().getHelper().getLocalURL(oriUrl);
                    Resource tldResource;
                    tldResource = Resource.newResource(url);
                    tlds.add(tldResource);
                }
                catch (Exception e)
                {
                    throw new IllegalArgumentException("Unable to locate the " + "tld resource in '"
                            + oriUrl.toString()
                            + "' in the bundle '"
                            + bs[0].getSymbolicName()
                            + "' while registering the "
                            + OSGiWebappConstants.REQUIRE_TLD_BUNDLE
                            + " of the manifest of "
                            + bundle.getSymbolicName(), e);
                }
            }
            if (!atLeastOneTldFound)
            {
                LOG.warn("No '/META-INF/*.tld' resources were found " + " in the bundle '"
                         + bs[0].getSymbolicName()
                         + "' while registering the "
                         + OSGiWebappConstants.REQUIRE_TLD_BUNDLE
                         + " of the manifest of "
                         + bundle.getSymbolicName());
            }
        }
        return tlds;
    }

    private PackageAdmin getBundleAdmin()
    {
        if (packageAdminServiceTracker == null)
        {
            Bundle bootBundle = ((BundleReference) OSGiWebappConstants.class.getClassLoader()).getBundle();
            packageAdminServiceTracker = new ServiceTracker(bootBundle.getBundleContext(), PackageAdmin.class.getName(), null);
            packageAdminServiceTracker.open();
        }
        return (PackageAdmin) packageAdminServiceTracker.getService();
    }

}
