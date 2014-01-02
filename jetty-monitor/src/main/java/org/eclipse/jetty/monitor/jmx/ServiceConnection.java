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

package org.eclipse.jetty.monitor.jmx;

import java.io.IOException;
import java.lang.management.ManagementFactory;

import javax.management.MBeanServer;
import javax.management.MBeanServerConnection;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXConnectorServer;
import javax.management.remote.JMXConnectorServerFactory;
import javax.management.remote.JMXServiceURL;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;


/* ------------------------------------------------------------ */
/**
 * ServerConnection
 * 
 * Provides ability to create a connection to either an external
 * JMX server, or a loopback connection to the internal one.
 */
public class ServiceConnection
{
    private static final Logger LOG = Log.getLogger(ServiceConnection.class);

    private String _serviceUrl;
    private MBeanServer _server;
    private JMXConnectorServer _connectorServer;
    private JMXConnector _serverConnector;
    private MBeanServerConnection _serviceConnection;
    
    /* ------------------------------------------------------------ */
    /**
     * Construct a loopback connection to an internal server
     * 
     * @throws IOException
     */
    public ServiceConnection()
        throws IOException
    {
        this(null);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Construct a connection to specified server
     * 
     * @param url URL of JMX server
     * @throws IOException
     */
    public ServiceConnection(String url)
        throws IOException
    {
        _serviceUrl = url;
    }
    
    /**
     * Retrieve an external URL for the JMX server
     * 
     * @return service URL
     */
    public String getServiceUrl()
    {
    	return _serviceUrl;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Retrieve a connection to MBean server
     * 
     * @return connection to MBean server
     */
    public MBeanServerConnection getConnection()
    {
        return _serviceConnection;
    }

    public void connect()
        throws IOException
    {
        if (_serviceConnection == null)
        {
            if (_serviceUrl == null)
                openLoopbackConnection();
            else
                openServerConnection(_serviceUrl);
        }
    }
    /* ------------------------------------------------------------ */
    /**
     * Open a loopback connection to local JMX server
     * 
     * @throws IOException
     */
    private void openLoopbackConnection()
        throws IOException
    {
        _server = ManagementFactory.getPlatformMBeanServer();       

        JMXServiceURL serviceUrl = new JMXServiceURL("service:jmx:rmi://");
        _connectorServer = JMXConnectorServerFactory.newJMXConnectorServer(serviceUrl, null, _server);
        _connectorServer.start();
        
        _serviceUrl = _connectorServer.getAddress().toString();
        
        _serverConnector = JMXConnectorFactory.connect(_connectorServer.getAddress());      
        _serviceConnection = _serverConnector.getMBeanServerConnection();
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Open a connection to remote JMX server
     * 
     * @param url
     * @throws IOException
     */
    private void openServerConnection(String url)
        throws IOException
    {
        _serviceUrl = url;
        
        JMXServiceURL serviceUrl = new JMXServiceURL(_serviceUrl);
        _serverConnector = JMXConnectorFactory.connect(serviceUrl);
        _serviceConnection = _serverConnector.getMBeanServerConnection();
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Close the connections
     */
    public void disconnect()
    {
        try
        {
            if (_serverConnector != null)
            {
                _serverConnector.close();
                _serviceConnection = null;
            }
            if (_connectorServer != null)
            {
                _connectorServer.stop();
                _connectorServer = null;
            }
        }
        catch (Exception ex)
        {
            LOG.debug(ex);
        }
    }
}
