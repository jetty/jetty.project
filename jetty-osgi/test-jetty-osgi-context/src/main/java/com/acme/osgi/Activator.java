//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

import org.eclipse.jetty.server.handler.ContextHandler;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;

/**
 * Bootstrap a ContextHandler
 */
public class Activator implements BundleActivator
{

    private ServiceRegistration _sr;

    /**
     *
     */
    @Override
    public void start(final BundleContext context) throws Exception
    {
        ContextHandler ch = new ContextHandler();
        ch.addEventListener(new ServletContextListener()
        {

            @Override
            public void contextInitialized(ServletContextEvent sce)
            {
                //System.err.println("Context is initialized");
            }

            @Override
            public void contextDestroyed(ServletContextEvent sce)
            {
                //System.err.println("CONTEXT IS DESTROYED!");                
            }
        });
        Dictionary props = new Hashtable();
        props.put("contextPath", "/acme");
        props.put("Jetty-ContextFilePath", "acme.xml");
        _sr = context.registerService(ContextHandler.class.getName(), ch, props);
    }

    /**
     * Stop the activator.
     *
     * @see org.osgi.framework.BundleActivator#stop(org.osgi.framework.BundleContext)
     */
    @Override
    public void stop(BundleContext context) throws Exception
    {
        _sr.unregister();
    }
}
