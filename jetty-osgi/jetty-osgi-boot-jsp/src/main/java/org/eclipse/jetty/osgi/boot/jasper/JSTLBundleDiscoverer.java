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
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;

import javax.servlet.Servlet;
import javax.servlet.jsp.JspContext;
import javax.servlet.jsp.JspFactory;

import org.apache.jasper.Constants;
import org.apache.jasper.compiler.Localizer;
import org.apache.jasper.xmlparser.ParserUtils;
import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.osgi.boot.JettyBootstrapActivator;
import org.eclipse.jetty.osgi.boot.utils.BundleFileLocatorHelper;
import org.eclipse.jetty.osgi.boot.utils.TldBundleDiscoverer;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * 
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
    private static final Logger LOG = Log.getLogger(JSTLBundleDiscoverer.class);
    

    /**
     * Default name of a class that belongs to the jstl bundle. From that class
     * we locate the corresponding bundle and register it as a bundle that
     * contains tld files.
     */
    private static String DEFAULT_JSTL_BUNDLE_CLASS = "org.apache.taglibs.standard.tag.el.core.WhenTag";

    // used to be "org.apache.jasper.runtime.JspFactoryImpl" but now
    // the standard tag library implementation are stored in a separate bundle.

    // DISABLED please use the tld bundle argument for the OSGiAppProvider
    // /**
    // * Default name of a class that belongs to the bundle where the Java
    // server Faces tld files are defined.
    // * This is the sun's reference implementation.
    // */
    // private static String DEFAUT_JSF_IMPL_CLASS =
    // "com.sun.faces.config.ConfigureListener";

    /**
     * Default jsp factory implementation. Idally jasper is osgified and we can
     * use services. In the mean time we statically set the jsp factory
     * implementation. bug #299733
     */
    private static String DEFAULT_JSP_FACTORY_IMPL_CLASS = "org.apache.jasper.runtime.JspFactoryImpl";

    public JSTLBundleDiscoverer()
    {
        fixupDtdResolution();

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
            // bug #299733
            JspFactory fact = JspFactory.getDefaultFactory();
            if (fact == null)
            { // bug #299733
              // JspFactory does a simple
              // Class.getForName("org.apache.jasper.runtime.JspFactoryImpl")
              // however its bundles does not import the jasper package
              // so it fails. let's help things out:
                fact = (JspFactory) JettyBootstrapActivator.class.getClassLoader().loadClass(DEFAULT_JSP_FACTORY_IMPL_CLASS).newInstance();
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
     * the root and/or in the lib folder (nice for PDE developement situations)
     * Unsupported: the bundle is a jar that embeds more jars.
     * 
     * @return array of URLs
     * @throws Exception
     */
    public URL[] getUrlsForBundlesWithTlds(DeploymentManager deployer, BundleFileLocatorHelper locatorHelper) throws Exception
    {

        ArrayList<URL> urls = new ArrayList<URL>();
        HashSet<Class<?>> classesToAddToTheTldBundles = new HashSet<Class<?>>();

        // Look for the jstl bundle
        // We assume the jstl's tlds are defined there.
        // We assume that the jstl bundle is imported by this bundle
        // So we can look for this class using this bundle's classloader:
        try
        {
            Class<?> jstlClass = JSTLBundleDiscoverer.class.getClassLoader().loadClass(DEFAULT_JSTL_BUNDLE_CLASS);

            classesToAddToTheTldBundles.add(jstlClass);
        }
        catch (ClassNotFoundException e)
        {
            LOG.info("jstl not on classpath", e);
        }
        for (Class<?> cl : classesToAddToTheTldBundles)
        {
            Bundle tldBundle = FrameworkUtil.getBundle(cl);
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
            }
        }
        return urls.toArray(new URL[urls.size()]);
    }

    /**
     * Jasper resolves the dtd when it parses a taglib descriptor. It uses this
     * code to do that:
     * ParserUtils.getClass().getResourceAsStream(resourcePath); where
     * resourcePath is for example:
     * /javax/servlet/jsp/resources/web-jsptaglibrary_1_2.dtd Unfortunately, the
     * dtd file is not in the exact same classloader as ParserUtils class and
     * the dtds are packaged in 2 separate bundles. OSGi does not look in the
     * dependencies' classloader when a resource is searched.
     * <p>
     * The workaround consists of setting the entity resolver. That is a patch
     * added to the version of glassfish-jasper-jetty. IT is also present in the
     * latest version of glassfish jasper. Could not use introspection to set
     * new value on a static friendly field :(
     * </p>
     */
    void fixupDtdResolution()
    {
        try
        {
            ParserUtils.setEntityResolver(new MyFixedupEntityResolver());

        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

    }

    /**
     * Instead of using the ParserUtil's classloader, we use a class that is
     * indeed next to the resource for sure.
     */
    static class MyFixedupEntityResolver implements EntityResolver
    {
        /**
         * Same values than in ParserUtils...
         */
        static final String[] CACHED_DTD_PUBLIC_IDS = { Constants.TAGLIB_DTD_PUBLIC_ID_11, Constants.TAGLIB_DTD_PUBLIC_ID_12,
                                                       Constants.WEBAPP_DTD_PUBLIC_ID_22, Constants.WEBAPP_DTD_PUBLIC_ID_23, };

        static final String[] CACHED_DTD_RESOURCE_PATHS = { Constants.TAGLIB_DTD_RESOURCE_PATH_11, Constants.TAGLIB_DTD_RESOURCE_PATH_12,
                                                           Constants.WEBAPP_DTD_RESOURCE_PATH_22, Constants.WEBAPP_DTD_RESOURCE_PATH_23, };

        static final String[] CACHED_SCHEMA_RESOURCE_PATHS = { Constants.TAGLIB_SCHEMA_RESOURCE_PATH_20, Constants.TAGLIB_SCHEMA_RESOURCE_PATH_21,
                                                              Constants.WEBAPP_SCHEMA_RESOURCE_PATH_24, Constants.WEBAPP_SCHEMA_RESOURCE_PATH_25, };

        public InputSource resolveEntity(String publicId, String systemId) throws SAXException
        {
            for (int i = 0; i < CACHED_DTD_PUBLIC_IDS.length; i++)
            {
                String cachedDtdPublicId = CACHED_DTD_PUBLIC_IDS[i];
                if (cachedDtdPublicId.equals(publicId))
                {
                    String resourcePath = CACHED_DTD_RESOURCE_PATHS[i];
                    InputStream input = null;
                    input = Servlet.class.getResourceAsStream(resourcePath);
                    if (input == null)
                    {
                        input = JspContext.class.getResourceAsStream(resourcePath);
                        if (input == null)
                        {
                            // if that failed try again with the original code:
                            // although it is likely not changed.
                            input = this.getClass().getResourceAsStream(resourcePath);
                        }
                    }
                    if (input == null) { throw new SAXException(Localizer.getMessage("jsp.error.internal.filenotfound", resourcePath)); }
                    InputSource isrc = new InputSource(input);
                    return isrc;
                }
            }

            return null;
        }
    }

}
