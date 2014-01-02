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

package com.acme.osgi;

import java.util.Dictionary;
import java.util.Hashtable;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.webapp.WebAppContext;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceRegistration;
import org.osgi.util.tracker.BundleTracker;

/**
 * Bootstrap a webapp
 * 
 * 
 */
public class Activator implements BundleActivator
{

    /**
     * 
     * @param context
     */
    public void start(BundleContext context) throws Exception
    {
        String serverName = "defaultJettyServer";
        
        /* Uncomment to create a different server instance to deploy to. Also change
         * TestJettyOSGiBootWebAppAsService to use the port 9999
         
        Server server = new Server();
        //do any setup on Server in here
        serverName = "fooServer";
        Dictionary serverProps = new Hashtable();
        //define the unique name of the server instance
        serverProps.put("managedServerName", serverName);
        serverProps.put("jetty.port", "9999");
        //let Jetty apply some configuration files to the Server instance
        serverProps.put("jetty.etc.config.urls", "file:/opt/jetty/etc/jetty.xml,file:/opt/jetty/etc/jetty-selector.xml,file:/opt/jetty/etc/jetty-deployer.xml");
        //register as an OSGi Service for Jetty to find 
        context.registerService(Server.class.getName(), server, serverProps);
        */
        
        //Create a webapp context as a Service and target it at the Server created above
        WebAppContext webapp = new WebAppContext();
        Dictionary props = new Hashtable();
        props.put("war",".");
        props.put("contextPath","/acme");
        props.put("managedServerName", serverName);
        context.registerService(ContextHandler.class.getName(),webapp,props);
    }

    /**
     * Stop the activator.
     * 
     * @see
     * org.osgi.framework.BundleActivator#stop(org.osgi.framework.BundleContext)
     */
    public void stop(BundleContext context) throws Exception
    {
    }
}
