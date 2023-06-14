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
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Objects;

import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.deploy.AppProvider;
import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.osgi.util.BundleFileLocatorHelperFactory;
import org.eclipse.jetty.server.Deployable;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OSGiApp
 *
 * Base class representing info about a WebAppContext/ContextHandler to be deployed into jetty.
 */
public class OSGiApp extends App
{
    private static final Logger LOG = LoggerFactory.getLogger(OSGiApp.class);

    protected Bundle _bundle;
    protected ServiceRegistration _registration;
    protected ContextHandler _contextHandler;
    protected String _pathToResourceBase;
    protected String _contextPath;
    protected Resource _bundleResource;

    /**
     * Get the install location of a Bundle as a Path
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

    /**
     * Convert a bundle installed location into a Resource, taking account of 
     * any locations that are actually packed jars, but without a ".jar" extension, eg
     * as found on equinox. Eg file:///a/b/c/org.eclipse.osgi/89/0/bundleFile
     * @param bundle the bundle
     * @return a Resource representing the bundle's installed location
     */
    private static Resource getBundleAsResource(Bundle bundle) throws Exception
    {
        String bundleOverrideLocation = bundle.getHeaders().get(OSGiWebappConstants.JETTY_BUNDLE_INSTALL_LOCATION_OVERRIDE);
        File bundleLocation = BundleFileLocatorHelperFactory.getFactory().getHelper().getBundleInstallLocation(bundle);
        File root = (bundleOverrideLocation == null ? bundleLocation : new File(bundleOverrideLocation));
        //Fix some osgiPaths.get( locations which point to an archive, but that doesn't end in .jar 
        URL url = BundleFileLocatorHelperFactory.getFactory().getHelper().getLocalURL(root.toURI().toURL());
        
        return ResourceFactory.root().newResource(url);
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
     * @param manager the DeploymentManager to which to deploy
     * @param provider the provider that discovered the context/webapp
     * @param bundle the bundle associated with the context/webapp
     */
    public OSGiApp(DeploymentManager manager, AppProvider provider, Bundle bundle)
    throws Exception
    {
        super(manager, provider, getBundlePath(bundle));

        _bundle = Objects.requireNonNull(bundle);
        _bundleResource = getBundleAsResource(bundle);
        
        //copy selected bundle headers into the properties
        Dictionary<String, String> headers = bundle.getHeaders();
        Enumeration<String> keys = headers.keys();
        while (keys.hasMoreElements())
        {
            String key = keys.nextElement();
            String val = headers.get(key);
            if (Deployable.ENVIRONMENT.equalsIgnoreCase(key) || OSGiWebappConstants.JETTY_ENVIRONMENT.equalsIgnoreCase(key))
                getProperties().put(Deployable.ENVIRONMENT, val);
            else if (Deployable.DEFAULTS_DESCRIPTOR.equalsIgnoreCase(key) || OSGiWebappConstants.JETTY_DEFAULT_WEB_XML_PATH.equalsIgnoreCase(key))
            {
                getProperties().put(Deployable.DEFAULTS_DESCRIPTOR, val);
            }
            else if (OSGiWebappConstants.JETTY_WEB_XML_PATH.equalsIgnoreCase(key))
            {
                getProperties().put(key, val);
            }
            else if (OSGiWebappConstants.JETTY_CONTEXT_FILE_PATH.equalsIgnoreCase(key))
            {
                getProperties().put(key, val);
            }
        }

        //set up the context path based on the supplied value, or the calculated default
        setContextPath(getContextPath(bundle));
    }

    public Resource getBundleResource()
    {
        return _bundleResource;
    }

    @Override
    public ContextHandler getContextHandler() throws Exception
    {
        if (_contextHandler == null)
                _contextHandler = getAppProvider().createContextHandler(this);
            return _contextHandler;
    }

    public void setContextHandler(ContextHandler contextHandler)
    {
        _contextHandler = contextHandler;
    }

    public String getPathToResourceBase()
    {
        return _pathToResourceBase;
    }

    public void setPathToResourceBase(String path)
    {
        _pathToResourceBase = path;
    }

    @Override
    public String getContextPath()
    {
        return _contextPath;
    }

    public void setContextPath(String contextPath)
    {
        _contextPath = contextPath;
    }

    public String getBundleSymbolicName()
    {
        return _bundle.getSymbolicName();
    }

    public String getBundleVersionAsString()
    {
        if (_bundle.getVersion() == null)
            return null;
        return _bundle.getVersion().toString();
    }

    public Bundle getBundle()
    {
        return _bundle;
    }

    public void setRegistration(ServiceRegistration registration)
    {
        _registration = registration;
    }

    public ServiceRegistration getRegistration()
    {
        return _registration;
    }

    /**
     * Register the Jetty deployed context/webapp as a service, as
     * according to the OSGi Web Application Specification.
     */
    public void registerAsOSGiService() throws Exception
    {
        if (_registration == null)
        {
            Dictionary<String, String> properties = new Hashtable<String, String>();
            properties.put(OSGiWebappConstants.WATERMARK, OSGiWebappConstants.WATERMARK);
            if (getBundleSymbolicName() != null)
                properties.put(OSGiWebappConstants.OSGI_WEB_SYMBOLICNAME, getBundleSymbolicName());
            if (getBundleVersionAsString() != null)
                properties.put(OSGiWebappConstants.OSGI_WEB_VERSION, getBundleVersionAsString());
            properties.put(OSGiWebappConstants.OSGI_WEB_CONTEXTPATH, getContextPath());
            ServiceRegistration rego = FrameworkUtil.getBundle(this.getClass()).getBundleContext().registerService(ContextHandler.class.getName(), getContextHandler(), properties);
            setRegistration(rego);
        }
    }

    protected void deregisterAsOSGiService() throws Exception
    {
        if (_registration == null)
            return;

        _registration.unregister();
        _registration = null;
    }
}
