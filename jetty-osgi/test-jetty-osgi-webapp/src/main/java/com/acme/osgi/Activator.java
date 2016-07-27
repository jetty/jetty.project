//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package com.acme.osgi;

import java.util.Dictionary;
import java.util.Hashtable;

import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.webapp.WebAppContext;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;

/**
 * Bootstrap a webapp
 * 
 * 
 */
public class Activator implements BundleActivator
{

    private ServiceRegistration _srA;
    private ServiceRegistration _srB;
    
    /**
     * 
     * @param context
     */
    public void start(BundleContext context) throws Exception
    {
        String serverName = "defaultJettyServer";
        
  
        
        //Create webappA as a Service and target it at the default server
        WebAppContext webapp = new WebAppContext();
        Dictionary props = new Hashtable();
        props.put("war","webappA");
        props.put("contextPath","/acme");
        props.put("managedServerName", "defaultJettyServer");
        _srA = context.registerService(WebAppContext.class.getName(),webapp,props);
        
        //Create a second webappB as a Service and target it at a custom Server
        //deployed by another bundle
        WebAppContext webappB = new WebAppContext();
        Dictionary propsB = new Hashtable();
        propsB.put("war", "webappB");
        propsB.put("contextPath", "/acme");
        propsB.put("managedServerName", "fooServer");
        _srB = context.registerService(WebAppContext.class.getName(), webappB, propsB);
    }

    /**
     * Stop the activator.
     * 
     * @see
     * org.osgi.framework.BundleActivator#stop(org.osgi.framework.BundleContext)
     */
    public void stop(BundleContext context) throws Exception
    {
        _srA.unregister(); 
        _srB.unregister();
    }
}
