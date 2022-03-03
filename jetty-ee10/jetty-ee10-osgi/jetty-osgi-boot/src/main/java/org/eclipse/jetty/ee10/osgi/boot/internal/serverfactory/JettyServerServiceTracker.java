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

package org.eclipse.jetty.ee9.osgi.boot.internal.serverfactory;

import java.util.Dictionary;
import java.util.Hashtable;

import org.eclipse.jetty.ee9.osgi.boot.OSGiServerConstants;
import org.eclipse.jetty.server.Server;
import org.osgi.framework.Bundle;
import org.osgi.framework.ServiceReference;
import org.osgi.util.tracker.ServiceTrackerCustomizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JettyServerServiceTracker
 *
 * Tracks instances of Jetty Servers, and configures them so that they can deploy
 * webapps or ContextHandlers discovered from the OSGi environment.
 */
public class JettyServerServiceTracker implements ServiceTrackerCustomizer
{
    private static Logger LOG = LoggerFactory.getLogger(JettyServerServiceTracker.class.getName());

    @Override
    public Object addingService(ServiceReference sr)
    {
        Bundle contributor = sr.getBundle();
        Server server = (Server)contributor.getBundleContext().getService(sr);
        String name = (String)sr.getProperty(OSGiServerConstants.MANAGED_JETTY_SERVER_NAME);
        if (name == null)
        {
            throw new IllegalArgumentException("The property " + OSGiServerConstants.MANAGED_JETTY_SERVER_NAME + " is mandatory");
        }
        if (LOG.isDebugEnabled())
            LOG.debug("Adding Server {}", name);
        ServerInstanceWrapper wrapper = new ServerInstanceWrapper(name);
        Dictionary<String, Object> props = new Hashtable<>();
        for (String key : sr.getPropertyKeys())
        {
            props.put(key, sr.getProperty(key));
        }
        try
        {
            wrapper.start(server, props);
            LOG.info("Started Server {}", name);
            return wrapper;
        }
        catch (Exception e)
        {
            LOG.warn("Failed to start server {}", name, e);
            return sr.getBundle().getBundleContext().getService(sr);
        }
    }

    @Override
    public void modifiedService(ServiceReference reference, Object service)
    {
        removedService(reference, service);
        addingService(reference);
    }

    @Override
    public void removedService(ServiceReference reference, Object service)
    {
        if (service instanceof ServerInstanceWrapper)
        {
            ServerInstanceWrapper wrapper = (ServerInstanceWrapper)service;
            try
            {
                wrapper.stop();
                LOG.info("Stopped Server {}", wrapper.getManagedServerName());
            }
            catch (Exception e)
            {
                LOG.warn("Failed to stop server {}", wrapper.getManagedServerName(), e);
            }
        }
    }
}
