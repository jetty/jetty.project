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

package com.acme.osgi;

import java.util.Dictionary;
import java.util.Hashtable;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
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
        props.put("Web-ContextPath", "/acme");
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
