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

package org.eclipse.jetty.osgi.boot;

import java.util.Dictionary;
import java.util.Hashtable;

import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.deploy.AppProvider;
import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceRegistration;



/**
 * AbstractOSGiApp
 *
 * Base class representing info about a webapp/ContextHandler that is deployed into Jetty.
 * 
 */
public abstract class AbstractOSGiApp extends App
{      
    protected Bundle _bundle;
    protected Dictionary _properties;
    protected ServiceRegistration _registration;

    /* ------------------------------------------------------------ */
    public AbstractOSGiApp(DeploymentManager manager, AppProvider provider, Bundle bundle, String originId)
    {
        super(manager, provider, originId);
        _properties = bundle.getHeaders();
        _bundle = bundle;
    }
    /* ------------------------------------------------------------ */
    public AbstractOSGiApp(DeploymentManager manager, AppProvider provider, Bundle bundle, Dictionary properties, String originId)
    {
        super(manager, provider, originId);
        _properties = properties;
        _bundle = bundle;
    }
    
    /* ------------------------------------------------------------ */
    public String getBundleSymbolicName()
    {
        return _bundle.getSymbolicName();
    }
    
    /* ------------------------------------------------------------ */
    public String getBundleVersionAsString()
    {
       if (_bundle.getVersion() == null)
           return null;
       return _bundle.getVersion().toString();
    }
    
    /* ------------------------------------------------------------ */
    public Bundle getBundle()
    {
        return _bundle;
    }
    
    /* ------------------------------------------------------------ */
    public void setRegistration (ServiceRegistration registration)
    {
        _registration = registration;
    }
    
    /* ------------------------------------------------------------ */
    public ServiceRegistration getRegistration ()
    {
        return _registration;
    }
    
    
    /* ------------------------------------------------------------ */
    public void registerAsOSGiService() throws Exception
    {
        if (_registration == null)
        {
            Dictionary<String,String> properties = new Hashtable<String,String>();
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

    /* ------------------------------------------------------------ */
    protected void deregisterAsOSGiService() throws Exception
    {
        if (_registration == null)
            return;

        _registration.unregister();
        _registration = null;
    }

}
