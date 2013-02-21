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

package org.eclipse.jetty.osgi.boot;

import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import org.eclipse.jetty.osgi.boot.internal.webapp.BundleFileLocatorHelperFactory;
import org.eclipse.jetty.osgi.boot.utils.internal.PackageAdminServiceTracker;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.MetaInfConfiguration;
import org.eclipse.jetty.webapp.WebAppContext;
import org.osgi.framework.Bundle;

public class OSGiMetaInfConfiguration extends MetaInfConfiguration
{
    private static final Logger LOG = Log.getLogger(OSGiMetaInfConfiguration.class);


    /** 
     * Inspect bundle fragments associated with the bundle of the webapp for web-fragment, resources, tlds.
     *
     * @see org.eclipse.jetty.webapp.MetaInfConfiguration#preConfigure(org.eclipse.jetty.webapp.WebAppContext)
     */
    @Override
    public void preConfigure(final WebAppContext context) throws Exception
    {
        List<Resource> frags = (List<Resource>) context.getAttribute(METAINF_FRAGMENTS);
        List<Resource> resfrags = (List<Resource>) context.getAttribute(METAINF_RESOURCES);
        List<Resource> tldfrags = (List<Resource>) context.getAttribute(METAINF_TLDS);

        Bundle[] fragments = PackageAdminServiceTracker.INSTANCE.getFragmentsAndRequiredBundles((Bundle)context.getAttribute(OSGiWebappConstants.JETTY_OSGI_BUNDLE));
        //TODO not convinced we need to do this, as we added any fragment jars to the MetaData.webInfJars in OSGiWebInfConfiguration, 
        //so surely the web-fragments and resources tlds etc can be discovered normally?
        for (Bundle frag : fragments)
        {
            URL webFrag = frag.getEntry("/META-INF/web-fragment.xml");
            Enumeration<URL> resEnum = frag.findEntries("/META-INF/resources", "*", true);
            Enumeration<URL> tldEnum = frag.findEntries("/META-INF", "*.tld", false);
            if (webFrag != null || (resEnum != null && resEnum.hasMoreElements()) || (tldEnum != null && tldEnum.hasMoreElements()))
            {
                try
                {
                    if (webFrag != null)
                    {
                        if (frags == null)
                        {
                            frags = new ArrayList<Resource>();
                            context.setAttribute(METAINF_FRAGMENTS, frags);
                        }
                        frags.add(Resource.newResource(BundleFileLocatorHelperFactory.getFactory().getHelper().getBundleInstallLocation(frag).toURI()));
                    }
                    if (resEnum != null && resEnum.hasMoreElements())
                    {
                        URL resourcesEntry = frag.getEntry("/META-INF/resources/");
                        if (resourcesEntry == null)
                        {
                            // probably we found some fragments to a
                            // bundle.
                            // those are already contributed.
                            // so we skip this.
                        }
                        else
                        {
                            if (resfrags == null)
                            {
                                resfrags = new ArrayList<Resource>();
                                context.setAttribute(METAINF_RESOURCES, resfrags);
                            }
                            resfrags.add(Resource.newResource(BundleFileLocatorHelperFactory.getFactory().getHelper().getLocalURL(resourcesEntry)));
                        }
                    }
                    if (tldEnum != null && tldEnum.hasMoreElements())
                    {
                        if (tldfrags == null)
                        {
                            tldfrags = new ArrayList<Resource>();
                            context.setAttribute(METAINF_TLDS, tldfrags);
                        }
                        while (tldEnum.hasMoreElements())
                        {
                            URL tldUrl = tldEnum.nextElement();
                            tldfrags.add(Resource.newResource(BundleFileLocatorHelperFactory.getFactory().getHelper().getLocalURL(tldUrl)));
                        }
                    }
                } 
                catch (Exception e)
                {
                    LOG.warn("Unable to locate the bundle " + frag.getBundleId(), e);
                }
            }
        }
        
        super.preConfigure(context);
    }
}
