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

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;

/**
 * Bootstrap a Server
 * 
 * 
 */
public class Activator implements BundleActivator
{

    private ServiceRegistration _sr;
    
    /**
     * 
     * @param context
     */
    public void start(BundleContext context) throws Exception
    {    
        Server server = new Server(9999);
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        server.setHandler(contexts);

        Dictionary serverProps = new Hashtable();
        //define the unique name of the server instance
        serverProps.put("managedServerName", "fooServer");
        //serverProps.put("jetty.http.port", "9999");
        //register as an OSGi Service for Jetty to find 
        _sr = context.registerService(Server.class.getName(), server, serverProps);
    }

    /**
     * Stop the activator.
     * 
     * @see
     * org.osgi.framework.BundleActivator#stop(org.osgi.framework.BundleContext)
     */
    public void stop(BundleContext context) throws Exception
    {
        _sr.unregister();
    }
}
