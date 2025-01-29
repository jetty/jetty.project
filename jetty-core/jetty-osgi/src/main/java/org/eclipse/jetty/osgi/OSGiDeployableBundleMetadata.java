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

package org.eclipse.jetty.osgi;

import java.io.File;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.Properties;

import org.eclipse.jetty.osgi.util.BundleFileLocatorHelperFactory;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.Version;

/**
 * Metadata useful for a deployment that will result in a {@link org.eclipse.jetty.server.handler.ContextHandler}
 */
public class OSGiDeployableBundleMetadata
{
    private static final String BUNDLE = OSGiDeployableBundleMetadata.class.getPackageName() + ".bundle";
    private static final String REGISTRATION = OSGiDeployableBundleMetadata.class.getPackageName() + ".registration";

    private final Bundle bundle;
    private final Path bundlePath;
    private final String contextPath;
    private final Properties properties = new Properties();
    private String pathToResourceBase;

    public OSGiDeployableBundleMetadata(Bundle bundle) throws Exception
    {
        this.bundle = bundle;
        this.bundlePath = getBundlePath(bundle);
        this.contextPath = getContextPath(bundle);
    }

    public static void deregisterAsOSGiService(ContextHandler contextHandler)
    {
        ServiceRegistration<?> serviceRegistration = (ServiceRegistration<?>)contextHandler.getAttribute(REGISTRATION);
        if (serviceRegistration == null)
            return;

        serviceRegistration.unregister();
        contextHandler.removeAttribute(REGISTRATION);
    }

    public static Bundle getBundle(ContextHandler contextHandler)
    {
        return (Bundle)contextHandler.getAttribute(BUNDLE);
    }

    /**
     * Get the install location of a Bundle as a Path
     *
     * @param bundle the Bundle whose location to return
     * @return the installed location of the Bundle as a Path
     */
    private static Path getBundlePath(Bundle bundle) throws Exception
    {
        String bundleOverrideLocation = bundle.getHeaders().get(OSGiWebappConstants.JETTY_BUNDLE_INSTALL_LOCATION_OVERRIDE);
        File bundleLocation = BundleFileLocatorHelperFactory.getFactory().getHelper().getBundleInstallLocation(bundle);
        File root = (bundleOverrideLocation == null ? bundleLocation : new File(bundleOverrideLocation));
        return Paths.get(root.toURI());
    }

    public static String getBundleSymbolicName(ContextHandler contextHandler)
    {
        Bundle bundle = getBundle(contextHandler);
        if (bundle == null)
            return null;
        return bundle.getSymbolicName();
    }

    public static String getBundleVersionAsString(ContextHandler contextHandler)
    {
        Bundle bundle = getBundle(contextHandler);
        if (bundle == null)
            return null;
        Version version = bundle.getVersion();
        if (version == null)
            return null;
        return version.toString();
    }

    /**
     * Get or create a contextPath from bundle headers and information
     *
     * @param bundle the bundle
     * @return a contextPath
     */
    private static String getContextPath(Bundle bundle)
    {
        Dictionary<?, ?> headers = bundle.getHeaders();
        String contextPath = (String)headers.get(OSGiWebappConstants.RFC66_WEB_CONTEXTPATH);
        if (contextPath == null)
        {
            // extract from the last token of the bundle's location:
            // (really ?could consider processing the symbolic name as an alternative
            // the location will often reflect the version.
            // maybe this is relevant when the file is a war)
            String location = bundle.getLocation();
            String[] toks = StringUtil.replace(location, '\\', '/').split("/");
            contextPath = toks[toks.length - 1];
            // remove .jar, .war etc:
            int lastDot = contextPath.lastIndexOf('.');
            if (lastDot != -1)
                contextPath = contextPath.substring(0, lastDot);
        }
        if (!contextPath.startsWith("/"))
            contextPath = "/" + contextPath;

        return contextPath;
    }

    /**
     * Register the Jetty deployed context/webapp as a service, as
     * according to the OSGi Web Application Specification.
     */
    public static void registerAsOSGiService(ContextHandler contextHandler)
    {
        ServiceRegistration<?> serviceRegistration = (ServiceRegistration<?>)contextHandler.getAttribute(REGISTRATION);
        if (serviceRegistration == null)
        {
            Dictionary<String, String> properties = new Hashtable<>();
            properties.put(OSGiWebappConstants.WATERMARK, OSGiWebappConstants.WATERMARK);

            String bundleSymbolicName = getBundleSymbolicName(contextHandler);
            if (StringUtil.isNotBlank(bundleSymbolicName))
                properties.put(OSGiWebappConstants.OSGI_WEB_SYMBOLICNAME, bundleSymbolicName);

            String bundleVersion = getBundleVersionAsString(contextHandler);
            if (StringUtil.isNotBlank(bundleVersion))
                properties.put(OSGiWebappConstants.OSGI_WEB_VERSION, bundleVersion);

            properties.put(OSGiWebappConstants.OSGI_WEB_CONTEXTPATH, contextHandler.getContextPath());

            serviceRegistration = FrameworkUtil.getBundle(OSGiDeployableBundleMetadata.class).getBundleContext().registerService(ContextHandler.class.getName(), contextHandler, properties);
            contextHandler.setAttribute(REGISTRATION, serviceRegistration);
        }
    }

    public static void setBundle(ContextHandler contextHandler, Bundle bundle)
    {
        contextHandler.setAttribute(BUNDLE, bundle);
    }

    public Bundle getBundle()
    {
        return bundle;
    }

    /**
     * Convert a bundle installed location into a Resource, taking account of
     * any locations that are actually packed jars, but without a ".jar" extension, eg
     * as found on equinox. Eg file:///a/b/c/org.eclipse.osgi/89/0/bundleFile
     *
     * @param resourceFactory the ResourceFactory to create Resource from
     * @return a Resource representing the bundle's installed location
     */
    public Resource getBundleResource(ResourceFactory resourceFactory) throws Exception
    {
        String bundleOverrideLocation = bundle.getHeaders().get(OSGiWebappConstants.JETTY_BUNDLE_INSTALL_LOCATION_OVERRIDE);
        File bundleLocation = BundleFileLocatorHelperFactory.getFactory().getHelper().getBundleInstallLocation(bundle);
        File root = (bundleOverrideLocation == null ? bundleLocation : new File(bundleOverrideLocation));
        // Fix some osgiPaths.get( locations which point to an archive, but that doesn't end in .jar
        URL url = BundleFileLocatorHelperFactory.getFactory().getHelper().getLocalURL(URIUtil.correctURI(root.toURI()).toURL());

        return resourceFactory.newResource(url);
    }

    public String getContextPath()
    {
        return contextPath;
    }

    public String getID()
    {
        return bundle.getSymbolicName();
    }

    public Path getPath()
    {
        return bundlePath;
    }

    public String getPathToResourceBase()
    {
        return pathToResourceBase;
    }

    public void setPathToResourceBase(String resourceBase)
    {
        this.pathToResourceBase = resourceBase;
    }

    public Properties getProperties()
    {
        return properties;
    }
}
