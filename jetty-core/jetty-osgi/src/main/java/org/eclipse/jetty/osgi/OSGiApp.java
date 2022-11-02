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

package org.eclipse.jetty.osgi;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Objects;

import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.deploy.AppProvider;
import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.ee.Deployable;
import org.eclipse.jetty.osgi.util.BundleFileLocatorHelperFactory;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.StringUtil;
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
    protected String _baseResource;
    protected String _contextPath;
    
    private static Path getBundlePath(Bundle bundle) throws Exception
    {
        String bundleOverrideLocation = bundle.getHeaders().get(OSGiWebappConstants.JETTY_BUNDLE_INSTALL_LOCATION_OVERRIDE);
        File bundleLocation = BundleFileLocatorHelperFactory.getFactory().getHelper().getBundleInstallLocation(bundle);
        File root = (bundleOverrideLocation == null ? bundleLocation : new File(bundleOverrideLocation));
        return Paths.get(BundleFileLocatorHelperFactory.getFactory().getHelper().getLocalURL(root.toURI().toURL()).toURI());
    }

    /**
     * @param manager the DeploymentManager to which to deploy
     * @param provider the provider that discovered the context/webapp
     * @param bundle the bundle associated with the context/webapp
     * @param path the path to the bundle
     */
    public OSGiApp(DeploymentManager manager, AppProvider provider, Bundle bundle)
    throws Exception
    {
        super(manager, provider, getBundlePath(bundle));

        _bundle = Objects.requireNonNull(bundle);
        
        //copy all bundle headers into the properties
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
        }

        //set up the context path based on the supplied value, or the calculated default
        setContextPath(getContextPath(bundle));
    }

    /**
     * Get or create a contextPath from bundle headers and information
     * 
     * @param bundle
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

    public String getBaseResource()
    {
        return _baseResource;
    }

    public void setBaseResource(String baseResource)
    {
        _baseResource = baseResource;
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
     * 
     * @throws Exception
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
