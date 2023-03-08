//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.deploy;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import javax.management.MBeanServer;
import javax.management.MBeanServerConnection;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXConnectorServer;
import javax.management.remote.JMXConnectorServerFactory;
import javax.management.remote.JMXServiceURL;

import org.eclipse.jetty.toolchain.test.IO;

/**
 * JmxServiceConnection
 *
 * Provides ability to create a connection to either an external
 * JMX server, or a loopback connection to the internal one.
 */
public class JmxServiceConnection
{
    private String serviceUrl;
    private MBeanServer server;
    private JMXConnectorServer connectorServer;
    private JMXConnector serverConnector;
    private MBeanServerConnection serviceConnection;

    /**
     * Construct a loopback connection to an internal server
     */
    public JmxServiceConnection()
    {
        this(null);
    }

    /**
     * Construct a connection to specified server
     *
     * @param url URL of JMX server
     */
    public JmxServiceConnection(String url)
    {
        serviceUrl = url;
    }

    /**
     * Retrieve an external URL for the JMX server
     *
     * @return service URL
     */
    public String getServiceUrl()
    {
        return serviceUrl;
    }

    /**
     * Retrieve a connection to MBean server
     *
     * @return connection to MBean server
     */
    public MBeanServerConnection getConnection()
    {
        return serviceConnection;
    }

    public void connect() throws IOException
    {
        if (serviceConnection == null)
        {
            if (serviceUrl == null)
            {
                openLoopbackConnection();
            }
            else
            {
                openServerConnection(serviceUrl);
            }
        }
    }

    /**
     * Open a loopback connection to local JMX server
     */
    private void openLoopbackConnection() throws IOException
    {
        server = ManagementFactory.getPlatformMBeanServer();

        JMXServiceURL serviceUrl = new JMXServiceURL("service:jmx:rmi://");
        connectorServer = JMXConnectorServerFactory.newJMXConnectorServer(serviceUrl, null, server);
        connectorServer.start();

        this.serviceUrl = connectorServer.getAddress().toString();

        serverConnector = JMXConnectorFactory.connect(connectorServer.getAddress());
        serviceConnection = serverConnector.getMBeanServerConnection();
    }

    /**
     * Open a connection to remote JMX server
     */
    private void openServerConnection(String url) throws IOException
    {
        serviceUrl = url;
        serverConnector = JMXConnectorFactory.connect(new JMXServiceURL(serviceUrl));
        serviceConnection = serverConnector.getMBeanServerConnection();
    }

    /**
     * Close the connections
     */
    public void disconnect()
    {
        IO.close(serverConnector);

        if (connectorServer != null)
        {
            try
            {
                connectorServer.stop();
            }
            catch (Exception ignore)
            {
                /* ignore */
            }
        }
    }
}
