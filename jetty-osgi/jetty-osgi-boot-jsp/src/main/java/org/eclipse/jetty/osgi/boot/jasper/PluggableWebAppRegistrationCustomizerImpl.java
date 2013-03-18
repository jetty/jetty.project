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

package org.eclipse.jetty.osgi.boot.jasper;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.regex.Pattern;

import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.osgi.boot.OSGiWebInfConfiguration;
import org.eclipse.jetty.osgi.boot.utils.BundleFileLocatorHelper;
import org.eclipse.jetty.osgi.boot.utils.WebappRegistrationCustomizer;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

/**
 * Plug bundles that contains tld files so that jasper will discover them and
 * set them up in jetty.
 * 
 * For example:
 * -Dorg.eclipse.jetty.osgi.tldbundles=org.springframework.web.servlet
 * ,com.opensymphony.module.sitemesh Otherwise use an attribute to the
 * WebAppDeployer &lt;New
 * class="org.eclipse.jetty.deploy.providers.WebAppProvider"&gt; .... &lt;Set
 * name="tldBundles"&gt;&ltProperty name="org.eclipse.jetty.osgi.tldsbundles"
 * default="" /&gt;&lt;/Set&gt; &lt;New&gt;
 */
public class PluggableWebAppRegistrationCustomizerImpl implements WebappRegistrationCustomizer
{
    /**
     * To plug into jasper bundles that contain tld files please use a list of
     * bundle's symbolic names:
     * -Djetty.osgi.tldbundles=org.springframework.web.servlet
     * ,com.opensymphony.module.sitemesh
     */
    public static final String SYS_PROP_TLD_BUNDLES = "org.eclipse.jetty.osgi.tldbundles";

    /**
     * Union of the tld bundles defined system wide and the one defines as an
     * attribute of the AppProvider.
     * 
     * @param provider
     * @return
     */
    private static Collection<String> getTldBundles(DeploymentManager deploymentManager)
    {
        String sysprop = System.getProperty(SYS_PROP_TLD_BUNDLES);
        String att = (String) deploymentManager.getContextAttribute(OSGiWebInfConfiguration.CONTAINER_BUNDLE_PATTERN);
        if (sysprop == null && att == null) { return Collections.emptySet(); }
        if (att == null)
        {
            att = sysprop;
        }
        else if (sysprop != null)
        {
            att = att + "," + sysprop;
        }

        Collection<String> tldbundles = new HashSet<String>();
        StringTokenizer tokenizer = new StringTokenizer(att, ", \n\r\t", false);
        while (tokenizer.hasMoreTokens())
        {
            tldbundles.add(tokenizer.nextToken());
        }
        return tldbundles;
    }

    /**
     * @return The location of the jars that contain tld files. Jasper will
     *         discover them.
     */
    public URL[] getJarsWithTlds(DeploymentManager deploymentManager, BundleFileLocatorHelper locatorHelper) throws Exception
    {
        // naive way of finding those bundles.
        // lots of assumptions: for example we assume a single version of each
        // bundle that would contain tld files.
        // this is probably good enough as those tlds are loaded system-wide on
        // jetty.
        // to do better than this we need to do it on a per webapp basis.
        // probably using custom properties in the ContextHandler service
        // and mirroring those in the MANIFEST.MF

        Bundle[] bundles = FrameworkUtil.getBundle(PluggableWebAppRegistrationCustomizerImpl.class).getBundleContext().getBundles();
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
                registerTldBundle(locatorHelper, bundle, urls);
           
            if (pattern != null && pattern.matcher(bundle.getSymbolicName()).matches())
                registerTldBundle(locatorHelper, bundle, urls);
        }

        return urls.toArray(new URL[urls.size()]);

    }

    /**
     * Resolves the bundle that contains tld files as a set of URLs that will be
     * passed to jasper as a URLClassLoader later on. Usually that would be a
     * single URL per bundle. But we do some more work if there are jars
     * embedded in the bundle.
     * 
     * The jasper TldScanner expects a URLClassloader to parse a jar for the
     * /META-INF/*.tld it may contain. We place the bundles that we know contain
     * such tag-libraries. Please note that it will work if and only if the
     * bundle is a jar (!) Currently we just hardcode the bundle that contains
     * the jstl implemenation.
     * 
     * A workaround when the tld cannot be parsed with this method is to copy
     * and paste it inside the WEB-INF of the webapplication where it is used.
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
    private void registerTldBundle(BundleFileLocatorHelper locatorHelper, Bundle bundle, Set<URL> urls) throws Exception
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