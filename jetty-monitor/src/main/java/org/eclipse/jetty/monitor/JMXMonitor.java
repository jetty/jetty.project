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

package org.eclipse.jetty.monitor;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import javax.management.MBeanServerConnection;

import org.eclipse.jetty.monitor.jmx.MonitorAction;
import org.eclipse.jetty.monitor.jmx.MonitorTask;
import org.eclipse.jetty.monitor.jmx.ServiceConnection;
import org.eclipse.jetty.xml.XmlConfiguration;

/* ------------------------------------------------------------ */
/**
 * JMXMonitor
 *
 * Performs monitoring of the values of the attributes of MBeans
 * and executes specified actions as well as sends notifications
 * of the specified events that have occurred.
 */
public class JMXMonitor
{
    private static JMXMonitor __monitor = new JMXMonitor();
    
    private String _serverUrl;
    private ServiceConnection _serviceConnection;

    private Set<MonitorAction> _actions = new HashSet<MonitorAction>();

    /* ------------------------------------------------------------ */
    /**
     * Constructs a JMXMonitor instance. Used for XML Configuration.
     * 
     * !! DO NOT INSTANTIATE EXPLICITLY !!
     */
    public JMXMonitor() {}

    /* ------------------------------------------------------------ */
    /**
     * Adds monitor actions to the monitor
     *
     * @param actions monitor actions to add
     * @return true if successful
     */
    public boolean addActions(MonitorAction... actions)
    {
        return getInstance().add(actions);
    }

    /* ------------------------------------------------------------ */
    /**
     * Removes monitor actions from the monitor
     * 
     * @param actions monitor actions to remove
     * @return true if successful
     */
    public boolean removeActions(MonitorAction... actions)
    {
        return getInstance().remove(actions);
    }

    /* ------------------------------------------------------------ */
    /**
     * Sets the JMX server URL
     *
     * @param url URL of the JMX server
     */
    public void setUrl(String url)
     {
        getInstance().set(url);
    }
    
    public MBeanServerConnection getConnection()
        throws IOException
    {
        return getInstance().get();
    }

    public static JMXMonitor getInstance()
    {
        return __monitor;
    }
       
    public static boolean addMonitorActions(MonitorAction... actions)
    {
        return getInstance().add(actions);
    }

    public static boolean removeMonitorActions(MonitorAction... actions)
    {
        return getInstance().remove(actions);
    }
    
    public static void setServiceUrl(String url)
    {
        getInstance().set(url);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Retrieves a connection to JMX service
     *
     * @return server connection
     * @throws IOException
     */
    public static MBeanServerConnection getServiceConnection()
        throws IOException
    {
        return getInstance().getConnection();
    }

    public static void main(final String args[]) throws Exception
    {
        XmlConfiguration.main(args);
    }
    
    private synchronized boolean add(MonitorAction... actions)
    {
        boolean result = true;

        for (MonitorAction action : actions)
        {
            if (!_actions.add(action))
            {
                result = false;
            }
            else
            {
                MonitorTask.schedule(action);
            }
        }
        
        return result;
    }
    
    private synchronized boolean remove(MonitorAction... actions)
    {
        boolean result = true;

        for (MonitorAction action : actions)
        {
            if (!_actions.remove(action))
            {
                result = false;
            }

            MonitorTask.cancel(action);
        }
        
        return result;
    }
    
    private synchronized void set(String url)
    {
        _serverUrl = url;

        if (_serviceConnection != null)
        {
            _serviceConnection.disconnect();
            _serviceConnection = null;
        }
    }
    
    private synchronized MBeanServerConnection get()
        throws IOException
    {
        if (_serviceConnection == null)
        {
            _serviceConnection = new ServiceConnection(_serverUrl);
            _serviceConnection.connect();
        }
        
        return _serviceConnection.getConnection();
    }
}
