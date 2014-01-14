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

package org.eclipse.jetty.osgi.boot.jasper;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.regex.Pattern;

import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.osgi.boot.OSGiWebInfConfiguration;
import org.eclipse.jetty.osgi.boot.utils.BundleFileLocatorHelper;
import org.eclipse.jetty.osgi.boot.utils.TldBundleDiscoverer;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;



/**
 * ContainerTldBundleDiscoverer
 * 
 * 
 * Use a System property to define bundles that contain tlds that need to
 * be treated by jasper as if they were on the jetty container's classpath.
 * 
 * The value of the property is evaluated against the DeploymentManager 
 * context attribute "org.eclipse.jetty.server.webapp.containerIncludeBundlePattern", 
 * which defines a pattern of matching bundle names.
 * 
 * The bundle locations are converted to URLs for jasper's use.
 * 
 * Eg:
 * -Dorg.eclipse.jetty.osgi.tldbundles=org.springframework.web.servlet,com.opensymphony.module.sitemesh
 * 
 */
public class ContainerTldBundleDiscoverer implements TldBundleDiscoverer
{
    /**
     * Comma separated list of names of bundles that contain tld files that should be
     * discoved by jasper as if they were on the container's classpath.
     * Eg:
     * -Djetty.osgi.tldbundles=org.springframework.web.servlet,com.opensymphony.module.sitemesh
     */
    public static final String SYS_PROP_TLD_BUNDLES = "org.eclipse.jetty.osgi.tldbundles";



    /**
     * Check the System property "org.eclipse.jetty.osgi.tldbundles" for names of
     * bundles that contain tlds and convert to URLs.
     * 
     * @return The location of the jars that contain tld files as URLs.
     */
    public URL[] getUrlsForBundlesWithTlds(DeploymentManager deploymentManager, BundleFileLocatorHelper locatorHelper) throws Exception
    {
        // naive way of finding those bundles.
        // lots of assumptions: for example we assume a single version of each
        // bundle that would contain tld files.
        // this is probably good enough as those tlds are loaded system-wide on
        // jetty.
        // to do better than this we need to do it on a per webapp basis.
        // probably using custom properties in the ContextHandler service
        // and mirroring those in the MANIFEST.MF

        Bundle[] bundles = FrameworkUtil.getBundle(ContainerTldBundleDiscoverer.class).getBundleContext().getBundles();
        HashSet<URL> urls = new HashSet<URL>();
        String tmp = System.getProperty(SYS_PROP_TLD_BUNDLES); //comma separated exact names
        List<String> sysNames =   new ArrayList<String>();
        if (tmp != null)
        {
            StringTokenizer tokenizer = new StringTokenizer(tmp, ", \n\r\t", false);
            while (tokenizer.hasMoreTokens())
                sysNames.add(tokenizer.nextToken());
        }
        tmp = (String) deploymentManager.getContextAttribute(OSGiWebInfConfiguration.CONTAINER_BUNDLE_PATTERN); //bundle name patterns
        Pattern pattern = (tmp==null? null : Pattern.compile(tmp));
        for (Bundle bundle : bundles)
        {
            if (sysNames.contains(bundle.getSymbolicName()))
                convertBundleLocationToURL(locatorHelper, bundle, urls);
           
            if (pattern != null && pattern.matcher(bundle.getSymbolicName()).matches())
                convertBundleLocationToURL(locatorHelper, bundle, urls);
        }

        return urls.toArray(new URL[urls.size()]);

    }

    /**
     * Resolves a bundle that contains tld files as a URL. The URLs are
     * used by jasper to discover the tld files.
     * 
     * Support only 2 types of packaging for the bundle: - the bundle is a jar
     * (recommended for runtime.) - the bundle is a folder and contain jars in
     * the root and/or in the lib folder (nice for PDE developement situations)
     * Unsupported: the bundle is a jar that embeds more jars.
     * 
     * @param locatorHelper
     * @param bundle
     * @param urls
     * @throws Exception
     */
    private void convertBundleLocationToURL(BundleFileLocatorHelper locatorHelper, Bundle bundle, Set<URL> urls) throws Exception
    {
        File jasperLocation = locatorHelper.getBundleInstallLocation(bundle);
        if (jasperLocation.isDirectory())
        {
            for (File f : jasperLocation.listFiles())
            {
                if (f.getName().endsWith(".jar") && f.isFile())
                {
                    urls.add(f.toURI().toURL());
                }
                else if (f.isDirectory() && f.getName().equals("lib"))
                {
                    for (File f2 : jasperLocation.listFiles())
                    {
                        if (f2.getName().endsWith(".jar") && f2.isFile())
                        {
                            urls.add(f2.toURI().toURL());
                        }
                    }
                }
            }
            urls.add(jasperLocation.toURI().toURL());
        }
        else
        {
            urls.add(jasperLocation.toURI().toURL());
        }
    }
}
