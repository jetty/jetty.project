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

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.util.component.LifeCycle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;

/**
 * Bootstrap a Server
 */
public class Activator implements BundleActivator
{

    private ServiceRegistration _sr;

    /**
     *
     */
    @Override
    public void start(BundleContext context) throws Exception
    {
        //For test purposes, use a random port
        Server server = new Server(0);
        server.getConnectors()[0].addEventListener(new LifeCycle.Listener()
        {

            @Override
            public void lifeCycleStarted(LifeCycle event)
            {
                System.setProperty("bundle.server.port", String.valueOf(((ServerConnector)event).getLocalPort()));
            }
        });
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        server.setHandler(contexts);
        // server.setDumpAfterStart(true);

        String[] list = new String[]{
            "org.eclipse.jetty.ee9.osgi.boot.OSGiWebInfConfiguration",
            "org.eclipse.jetty.ee9.webapp.WebXmlConfiguration",
            "org.eclipse.jetty.ee9.webapp.MetaInfConfiguration",
            "org.eclipse.jetty.ee9.webapp.FragmentConfiguration",
            "org.eclipse.jetty.ee9.webapp.JettyWebXmlConfiguration"
        };
        server.setAttribute("org.eclipse.jetty.ee9.webapp.configuration", list);

        Dictionary serverProps = new Hashtable();
        //define the unique name of the server instance
        serverProps.put("managedServerName", "fooServer");
        //Could also instead call serverProps.put("jetty.http.port", "9999");
        //register as an OSGi Service for Jetty to find 
        _sr = context.registerService(Server.class.getName(), server, serverProps);
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
