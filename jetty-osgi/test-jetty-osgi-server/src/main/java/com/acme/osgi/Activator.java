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

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.util.component.AbstractLifeCycle.AbstractLifeCycleListener;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.webapp.Configuration;
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
        server.getConnectors()[0].addLifeCycleListener(new AbstractLifeCycleListener()
        {

            /**
             * @see org.eclipse.jetty.util.component.AbstractLifeCycle.AbstractLifeCycleListener#lifeCycleStarted(org.eclipse.jetty.util.component.LifeCycle)
             */
            @Override
            public void lifeCycleStarted(LifeCycle event)
            {
                System.setProperty("bundle.server.port", String.valueOf(((ServerConnector)event).getLocalPort()));
                super.lifeCycleStarted(event);
            }
        });
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        server.setHandler(contexts);
        //server.setDumpAfterStart(true);

        Configuration.ClassList list = new Configuration.ClassList(new String[]{
            "org.eclipse.jetty.osgi.boot.OSGiWebInfConfiguration",
            "org.eclipse.jetty.webapp.WebXmlConfiguration",
            "org.eclipse.jetty.webapp.MetaInfConfiguration",
            "org.eclipse.jetty.webapp.FragmentConfiguration",
            "org.eclipse.jetty.webapp.JettyWebXmlConfiguration"
        });
        server.addBean(list);
        server.setAttribute("org.eclipse.jetty.webapp.configuration", list);
        Configuration.ClassList.setServerDefault(server);

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
