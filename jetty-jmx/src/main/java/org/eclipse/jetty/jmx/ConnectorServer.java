// ========================================================================
// Copyright (c) 2009-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses.
// ========================================================================

package org.eclipse.jetty.jmx;

import java.lang.management.ManagementFactory;
import java.util.Map;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.remote.JMXConnectorServer;
import javax.management.remote.JMXConnectorServerFactory;
import javax.management.remote.JMXServiceURL;

import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.thread.ShutdownThread;


/* ------------------------------------------------------------ */
/**
 * AbstractLifeCycle wrapper for JMXConnector Server
 */
public class ConnectorServer extends AbstractLifeCycle
{
    JMXConnectorServer _connectorServer;
    
    /* ------------------------------------------------------------ */
    /**
     * Constructs connector server
     * 
     * @param serviceURL the address of the new connector server.
     * The actual address of the new connector server, as returned 
     * by its getAddress method, will not necessarily be exactly the same.
     * @param name object name string to be assigned to connector server bean
     * @throws Exception
     */
    public ConnectorServer(JMXServiceURL serviceURL, String name)
        throws Exception
    {
        this(serviceURL, null, name);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Constructs connector server
     * 
     * @param serviceURL the address of the new connector server.
     * The actual address of the new connector server, as returned 
     * by its getAddress method, will not necessarily be exactly the same.
     * @param environment  a set of attributes to control the new connector
     * server's behavior. This parameter can be null. Keys in this map must
     * be Strings. The appropriate type of each associated value depends on
     * the attribute. The contents of environment are not changed by this call. 
     * @param name object name string to be assigned to connector server bean
     * @throws Exception
     */
    public ConnectorServer(JMXServiceURL serviceURL, Map<String,?> environment, String name)
         throws Exception
    {
        MBeanServer mbeanServer = ManagementFactory.getPlatformMBeanServer();
        _connectorServer = JMXConnectorServerFactory.newJMXConnectorServer(serviceURL, environment, mbeanServer);
        mbeanServer.registerMBean(_connectorServer,new ObjectName(name));
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.util.component.AbstractLifeCycle#doStart()
     */
    @Override
    public void doStart()
        throws Exception
    {
        _connectorServer.start();
        ShutdownThread.register(0, this);       
        
        Log.info("JMX Remote URL: {}", _connectorServer.getAddress().toString());
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.util.component.AbstractLifeCycle#doStop()
     */
    @Override
    public void doStop()
        throws Exception
    {
        ShutdownThread.deregister(this);
        _connectorServer.stop();
    }
}
