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
import java.util.Set;

import jakarta.servlet.jsp.JspFactory;
import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.ee9.osgi.boot.JettyBootstrapActivator;
import org.eclipse.jetty.ee9.osgi.boot.utils.BundleFileLocatorHelper;
import org.eclipse.jetty.ee9.osgi.boot.utils.TldBundleDiscoverer;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JSTLBundleDiscoverer
 *
 * Fix various shortcomings with the way jasper parses the tld files. Plugs the
 * JSTL tlds assuming that they are packaged with the bundle that contains the
 * JSTL classes.
 * <p>
 * Pluggable tlds at the server level are handled by
 * {@link ContainerTldBundleDiscoverer}.
 * </p>
 */
public class JSTLBundleDiscoverer implements TldBundleDiscoverer
{
    private static final Logger LOG = LoggerFactory.getLogger(JSTLBundleDiscoverer.class);

    /**
     * Default name of a class that belongs to the jstl bundle. From that class
     * we locate the corresponding bundle and register it as a bundle that
     * contains tld files.
     */
    private static String DEFAULT_JSTL_BUNDLE_CLASS = "org.apache.taglibs.standard.tag.el.core.WhenTag";

    /**
     * Default jsp factory implementation. Idally jasper is osgified and we can
     * use services. In the mean time we statically set the jsp factory
     * implementation. bug #299733
     */
    private static String DEFAULT_JSP_FACTORY_IMPL_CLASS = "org.apache.jasper.runtime.JspFactoryImpl";

    private static final Set<URL> __tldBundleCache = new HashSet<URL>();

    public JSTLBundleDiscoverer()
    {
        try
        {
            // sanity check:
            Class cl = getClass().getClassLoader().loadClass("org.apache.jasper.servlet.JspServlet");
        }
        catch (Exception e)
        {
            LOG.warn("Unable to locate the JspServlet: jsp support unavailable.", e);
            return;
        }
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
     * the root and/or in the lib folder (nice for PDE development situations)
     * Unsupported: the bundle is a jar that embeds more jars.
     *
     * @return array of URLs
     * @throws Exception In case of errors during resolving TLDs files
     */
    @Override
    public URL[] getUrlsForBundlesWithTlds(DeploymentManager deployer, BundleFileLocatorHelper locatorHelper) throws Exception
    {

        ArrayList<URL> urls = new ArrayList<URL>();
        Class<?> jstlClass = null;

        // Look for the jstl bundle
        // We assume the jstl's tlds are defined there.
        // We assume that the jstl bundle is imported by this bundle
        // So we can look for this class using this bundle's classloader:
        try
        {
            jstlClass = JSTLBundleDiscoverer.class.getClassLoader().loadClass(DEFAULT_JSTL_BUNDLE_CLASS);
        }
        catch (ClassNotFoundException e)
        {
            LOG.info("jstl not on classpath", e);
        }

        if (jstlClass != null)
        {
            //get the bundle containing jstl
            Bundle tldBundle = FrameworkUtil.getBundle(jstlClass);
            File tldBundleLocation = locatorHelper.getBundleInstallLocation(tldBundle);

            if (tldBundleLocation != null && tldBundleLocation.isDirectory())
            {
                // try to find the jar files inside this folder
                for (File f : tldBundleLocation.listFiles())
                {
                    if (f.getName().endsWith(".jar") && f.isFile())
                    {
                        urls.add(f.toURI().toURL());
                    }
                    else if (f.isDirectory() && f.getName().equals("lib"))
                    {
                        for (File f2 : tldBundleLocation.listFiles())
                        {
                            if (f2.getName().endsWith(".jar") && f2.isFile())
                            {
                                urls.add(f2.toURI().toURL());
                            }
                        }
                    }
                }
            }
            else if (tldBundleLocation != null)
            {
                urls.add(tldBundleLocation.toURI().toURL());

                String pattern = (String)deployer.getContextAttribute("org.eclipse.jetty.server.webapp.containerIncludeBundlePattern");
                pattern = (pattern == null ? "" : pattern);
                if (!pattern.contains(tldBundle.getSymbolicName()))
                {
                    pattern += "|" + tldBundle.getSymbolicName();
                    deployer.setContextAttribute("org.eclipse.jetty.server.webapp.containerIncludeBundlePattern", pattern);
                }
            }
        }

        return urls.toArray(new URL[urls.size()]);
    }
}
