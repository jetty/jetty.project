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

import java.nio.file.Path;
import java.util.Dictionary;
import java.util.Hashtable;

import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.deploy.AppProvider;
import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.server.handler.ContextHandler;
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

    public AbstractOSGiApp(DeploymentManager manager, AppProvider provider, Bundle bundle, Path path)
    {
        this(manager, provider, bundle, bundle.getHeaders(), path);
    }

    public AbstractOSGiApp(DeploymentManager manager, AppProvider provider, Bundle bundle, Dictionary<?, ?> properties, Path path)
    {
        super(manager, provider, path);
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
}
