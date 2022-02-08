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

package org.eclipse.jetty.osgi.boot;

import java.io.File;
import java.net.URL;
import java.util.Dictionary;
import java.util.Hashtable;

import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.deploy.AppProvider;
import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.resource.Resource;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AbstractOSGiApp
 *
 * Base class representing info about a webapp/ContextHandler that is deployed into Jetty.
 */
public abstract class AbstractOSGiApp extends App
{
    private static final Logger LOG = LoggerFactory.getLogger(AbstractOSGiApp.class);

    protected Bundle _bundle;
    protected Dictionary<?, ?> _properties;
    protected ServiceRegistration _registration;

    public AbstractOSGiApp(DeploymentManager manager, AppProvider provider, Bundle bundle, String originId)
    {
        this(manager, provider, bundle, bundle.getHeaders(), originId);
    }

    public AbstractOSGiApp(DeploymentManager manager, AppProvider provider, Bundle bundle, Dictionary<?, ?> properties, String originId)
    {
        super(manager, provider, originId);
        _properties = properties;
        _bundle = bundle;
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

    protected Resource getFileAsResource(String dir, String file)
    {
        Resource r = null;
        try
        {
            File asFile = new File(dir, file);
            if (asFile.exists())
                r = Resource.newResource(asFile);
        }
        catch (Exception e)
        {
            r = null;
        }
        return r;
    }

    protected Resource getFileAsResource(String file)
    {
        Resource r = null;
        try
        {
            File asFile = new File(file);
            if (asFile.exists())
                r = Resource.newResource(asFile);
        }
        catch (Exception e)
        {
            r = null;
        }
        return r;
    }

    protected Resource findFile(String fileName, String jettyHome, String bundleOverrideLocation, Bundle containingBundle)
    {
        Resource res = null;

        //try to find the context file in the filesystem
        if (fileName.startsWith("/"))
            res = getFileAsResource(fileName);
        if (res != null)
            return res;

        //try to find it relative to jetty home
        if (jettyHome != null)
        {
            if (jettyHome.startsWith("\"") || jettyHome.startsWith("'"))
                jettyHome = jettyHome.substring(1);
            if (jettyHome.endsWith("\"") || (jettyHome.endsWith("'")))
                jettyHome = jettyHome.substring(0, jettyHome.length() - 1);

            res = getFileAsResource(jettyHome, fileName);
        }
        if (res != null)
            return res;

        //try to find it relative to an override location that has been specified               
        if (bundleOverrideLocation != null)
        {
            try (Resource location = Resource.newResource(bundleOverrideLocation))
            {
                res = location.addPath(fileName);
            }
            catch (Exception e)
            {
                LOG.warn("Unable to find relative override location: {}", bundleOverrideLocation, e);
            }
        }
        if (res != null)
            return res;

        //try to find it relative to the bundle in which it is being deployed
        if (containingBundle != null)
        {
            if (fileName.startsWith("./"))
                fileName = fileName.substring(1);

            if (!fileName.startsWith("/"))
                fileName = "/" + fileName;

            URL entry = _bundle.getEntry(fileName);
            if (entry != null)
                res = Resource.newResource(entry);
        }

        return res;
    }
}
