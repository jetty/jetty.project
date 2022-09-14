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

package org.eclipse.jetty.ee9.osgi.boot.jasper;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.regex.Pattern;

import jakarta.servlet.jsp.JspFactory;
import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.ee9.osgi.boot.JettyBootstrapActivator;
import org.eclipse.jetty.ee9.osgi.boot.OSGiMetaInfConfiguration;
import org.eclipse.jetty.ee9.osgi.boot.utils.BundleFileLocatorHelper;
import org.eclipse.jetty.ee9.osgi.boot.utils.TldBundleDiscoverer;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ContainerTldBundleDiscoverer
 *
 * Finds bundles that are considered as on the container classpath that
 * contain tlds.
 *
 * The System property org.eclipse.jetty.ee9.osgi.tldbundles is a comma
 * separated list of exact symbolic names of bundles that have container classpath
 * tlds.
 *
 * The DeploymentManager context attribute "org.eclipse.jetty.server.webapp.containerIncludeBundlePattern"
 * can be used to define a pattern of symbolic names of bundles that contain container
 * classpath tlds.
 *
 * The matching bundles are converted to URLs that are put onto a special classloader that acts as the
 * parent classloader for contexts deployed by the jetty Server instance (see ServerInstanceWrapper).
 *
 * It also discovers the bundle that contains the jstl taglib and adds it into the
 * "org.eclipse.jetty.server.webapp.containerIncludeBundlePattern" (if it is not already there) so
 * that the WebInfOSGiConfiguration class will add the jstl taglib bundle into the list of container
 * resources.
 *
 * Eg:
 * -Dorg.eclipse.jetty.ee9.osgi.tldbundles=org.springframework.web.servlet,com.opensymphony.module.sitemesh
 */
public class ContainerTldBundleDiscoverer implements TldBundleDiscoverer
{

    private static final Logger LOG = LoggerFactory.getLogger(ContainerTldBundleDiscoverer.class);

    private static String DEFAULT_JSP_FACTORY_IMPL_CLASS = "org.apache.jasper.runtime.JspFactoryImpl";
    /**
     * Default name of a class that belongs to the jstl bundle. From that class
     * we locate the corresponding bundle and register it as a bundle that
     * contains tld files.
     */
    private static String DEFAULT_JSTL_BUNDLE_CLASS = "org.apache.taglibs.standard.tag.rt.core.WhenTag";

    private Bundle jstlBundle = null;

    /**
     * Check the System property "org.eclipse.jetty.ee9.osgi.tldbundles" for names of
     * bundles that contain tlds and convert to URLs.
     *
     * @return The location of the jars that contain tld files as URLs.
     */
    @Override
    public URL[] getUrlsForBundlesWithTlds(DeploymentManager deploymentManager, BundleFileLocatorHelper locatorHelper) throws Exception
    {
        if (!isJspAvailable())
        {
            return new URL[0];
        }

        if (jstlBundle == null)
            jstlBundle = findJstlBundle();

        final Bundle[] bundles = FrameworkUtil.getBundle(ContainerTldBundleDiscoverer.class).getBundleContext().getBundles();
        HashSet<URL> urls = new HashSet<URL>();
        String tmp = System.getProperty(OSGiMetaInfConfiguration.SYS_PROP_TLD_BUNDLES); //comma separated exact names
        List<String> sysNames = new ArrayList<String>();
        if (tmp != null)
        {
            StringTokenizer tokenizer = new StringTokenizer(tmp, ", \n\r\t", false);
            while (tokenizer.hasMoreTokens())
            {
                sysNames.add(tokenizer.nextToken());
            }
        }
        tmp = (String)deploymentManager.getContextAttribute(OSGiMetaInfConfiguration.CONTAINER_BUNDLE_PATTERN); //bundle name patterns

        Pattern pattern = (tmp == null ? null : Pattern.compile(tmp));

        //check that the jstl bundle is not already included in the pattern, and include it if it is not because
        //subsequent classes such as OSGiWebInfConfiguration use this pattern to determine which jars are
        //considered to be on the container classpath
        if (jstlBundle != null)
        {
            if (pattern == null)
            {
                pattern = Pattern.compile(jstlBundle.getSymbolicName());
                deploymentManager.setContextAttribute(OSGiMetaInfConfiguration.CONTAINER_BUNDLE_PATTERN, jstlBundle.getSymbolicName());
            }
            else if (!(pattern.matcher(jstlBundle.getSymbolicName()).matches()))
            {
                String s = tmp + "|" + jstlBundle.getSymbolicName();
                pattern = Pattern.compile(s);
                deploymentManager.setContextAttribute(OSGiMetaInfConfiguration.CONTAINER_BUNDLE_PATTERN, s);
            }
        }

        for (Bundle bundle : bundles)
        {
            if (sysNames.contains(bundle.getSymbolicName()))
                convertBundleLocationToURL(locatorHelper, bundle, urls);
            else if (pattern != null && pattern.matcher(bundle.getSymbolicName()).matches())
                convertBundleLocationToURL(locatorHelper, bundle, urls);
        }

        return urls.toArray(new URL[urls.size()]);
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
        }
        catch (Exception e)
        {
            LOG.warn("Unable to locate the JspServlet: jsp support unavailable.", e);
            return false;
        }
        return true;
    }

    /**
     * Some versions of JspFactory do Class.forName, which probably won't work in an
     * OSGi environment.
     */
    public void fixJspFactory()
    {
        try
        {
            Class<jakarta.servlet.ServletContext> servletContextClass = jakarta.servlet.ServletContext.class;
            // bug #299733
            JspFactory fact = JspFactory.getDefaultFactory();
            if (fact == null)
            { // bug #299733
                // JspFactory does a simple
                // Class.getForName("org.apache.jasper.runtime.JspFactoryImpl")
                // however its bundles does not import the jasper package
                // so it fails. let's help things out:
                fact = (JspFactory)JettyBootstrapActivator.class.getClassLoader()
                    .loadClass(DEFAULT_JSP_FACTORY_IMPL_CLASS).getDeclaredConstructor().newInstance();
                JspFactory.setDefaultFactory(fact);
            }
        }
        catch (Exception e)
        {
            LOG.warn("Unable to set the JspFactory: jsp support incomplete.", e);
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
            jstlClass = JSTLBundleDiscoverer.class.getClassLoader().loadClass(DEFAULT_JSTL_BUNDLE_CLASS);
        }
        catch (ClassNotFoundException e)
        {
            LOG.info("jstl not on classpath", e);
        }

        if (jstlClass != null)
            //get the bundle containing jstl
            return FrameworkUtil.getBundle(jstlClass);

        return null;
    }

    /**
     * Resolves a bundle that contains tld files as a URL. The URLs are
     * used by jasper to discover the tld files.
     *
     * Support only 2 types of packaging for the bundle: - the bundle is a jar
     * (recommended for runtime.) - the bundle is a folder and contain jars in
     * the root and/or in the lib folder (nice for PDE development situations)
     * Unsupported: the bundle is a jar that embeds more jars.
     */
    private void convertBundleLocationToURL(BundleFileLocatorHelper locatorHelper, Bundle bundle, Set<URL> urls) throws Exception
    {
        File jasperLocation = locatorHelper.getBundleInstallLocation(bundle);
        if (jasperLocation.isDirectory())
        {
            for (File f : jasperLocation.listFiles())
            {
                if (FileID.isJavaArchive(f.getName()) && f.isFile())
                {
                    urls.add(f.toURI().toURL());
                }
                else if (f.isDirectory() && f.getName().equals("lib"))
                {
                    for (File f2 : jasperLocation.listFiles())
                    {
                        if (FileID.isJavaArchive(f2.getName()) && f2.isFile())
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
