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

package org.eclipse.jetty.osgi.boot.internal.serverfactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.eclipse.jetty.osgi.boot.OSGiServerConstants;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.osgi.framework.Bundle;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceReference;

/**
 * JettyServerServiceTracker
 * 
 * Tracks instances of Jetty Servers, and configures them so that they can deploy 
 * webapps or ContextHandlers discovered from the OSGi environment.
 * 
 */
public class JettyServerServiceTracker implements ServiceListener
{
    private static Logger LOG = Log.getLogger(JettyServerServiceTracker.class.getName());


    /** The context-handler to deactivate indexed by ServerInstanceWrapper */
    private Map<ServiceReference, ServerInstanceWrapper> _indexByServiceReference = new HashMap<ServiceReference, ServerInstanceWrapper>();

    /**
     * Stops each one of the registered servers.
     */
    public void stop()
    {
        for (ServerInstanceWrapper wrapper : _indexByServiceReference.values())
        {
            try
            {
                wrapper.stop();
            }
            catch (Throwable t)
            {
                LOG.warn(t);
            }
        }
    }

    /**
     * Receives notification that a service has had a lifecycle change.
     * 
     * @param ev The <code>ServiceEvent</code> object.
     */
    public void serviceChanged(ServiceEvent ev)
    {
        ServiceReference sr = ev.getServiceReference();
        switch (ev.getType())
        {
            case ServiceEvent.MODIFIED:
            case ServiceEvent.UNREGISTERING:
            {
                ServerInstanceWrapper instance = unregisterInIndex(ev.getServiceReference());
                if (instance != null)
                {
                    try
                    {
                        instance.stop();
                    }
                    catch (Exception e)
                    {
                        LOG.warn(e);
                    }
                }

                if (ev.getType() == ServiceEvent.UNREGISTERING)
                {
                    break;
                }
                else
                {
                    // modified, meaning: we reload it. now that we stopped it;
                    // we can register it.
                }
            }
            case ServiceEvent.REGISTERED:
            {
                try
                {
                    Bundle contributor = sr.getBundle();
                    Server server = (Server) contributor.getBundleContext().getService(sr);
                    ServerInstanceWrapper wrapper = registerInIndex(server, sr);
                    Properties props = new Properties();
                    for (String key : sr.getPropertyKeys())
                    {
                        Object value = sr.getProperty(key);
                        props.put(key, value);
                    }
                    wrapper.start(server, props);
                }
                catch (Exception e)
                {
                    LOG.warn(e);
                }
                break;
            }
        }
    }

    private ServerInstanceWrapper registerInIndex(Server server, ServiceReference sr)
    {
        String name = (String) sr.getProperty(OSGiServerConstants.MANAGED_JETTY_SERVER_NAME);
        if (name == null) { throw new IllegalArgumentException("The property " + OSGiServerConstants.MANAGED_JETTY_SERVER_NAME + " is mandatory"); }
        ServerInstanceWrapper wrapper = new ServerInstanceWrapper(name);
        _indexByServiceReference.put(sr, wrapper);
        return wrapper;
    }

    /**
     * Returns the ContextHandler to stop.
     * 
     * @param reg
     * @return the ContextHandler to stop.
     */
    private ServerInstanceWrapper unregisterInIndex(ServiceReference sr)
    {
        ServerInstanceWrapper handler = _indexByServiceReference.remove(sr);
        if (handler == null)
        {
            LOG.warn("Unknown Jetty Server ServiceReference: ", sr);
            return null;
        }
       
        return handler;
    }
}
