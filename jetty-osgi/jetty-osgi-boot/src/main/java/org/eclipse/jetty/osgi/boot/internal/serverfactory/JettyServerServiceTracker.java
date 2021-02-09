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

package org.eclipse.jetty.osgi.boot.internal.serverfactory;

import java.util.Dictionary;
import java.util.Hashtable;

import org.eclipse.jetty.osgi.boot.OSGiServerConstants;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.osgi.framework.Bundle;
import org.osgi.framework.ServiceReference;
import org.osgi.util.tracker.ServiceTrackerCustomizer;

/**
 * JettyServerServiceTracker
 *
 * Tracks instances of Jetty Servers, and configures them so that they can deploy
 * webapps or ContextHandlers discovered from the OSGi environment.
 */
public class JettyServerServiceTracker implements ServiceTrackerCustomizer
{
    private static Logger LOG = Log.getLogger(JettyServerServiceTracker.class.getName());

    /**
     * @see org.osgi.util.tracker.ServiceTrackerCustomizer#addingService(org.osgi.framework.ServiceReference)
     */
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
            LOG.warn(e);
            return sr.getBundle().getBundleContext().getService(sr);
        }
    }

    /**
     * @see org.osgi.util.tracker.ServiceTrackerCustomizer#modifiedService(org.osgi.framework.ServiceReference, java.lang.Object)
     */
    @Override
    public void modifiedService(ServiceReference reference, Object service)
    {
        removedService(reference, service);
        addingService(reference);
    }

    /**
     * @see org.osgi.util.tracker.ServiceTrackerCustomizer#removedService(org.osgi.framework.ServiceReference, java.lang.Object)
     */
    @Override
    public void removedService(ServiceReference reference, Object service)
    {
        if (service instanceof ServerInstanceWrapper)
        {
            try
            {
                ServerInstanceWrapper wrapper = (ServerInstanceWrapper)service;
                wrapper.stop();
                LOG.info("Stopped Server {}", wrapper.getManagedServerName());
            }
            catch (Exception e)
            {
                LOG.warn(e);
            }
        }
    }
}
