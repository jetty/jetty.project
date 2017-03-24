//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.jmx;

import org.eclipse.jetty.util.HostPort;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.ShutdownThread;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.remote.JMXConnectorServer;
import javax.management.remote.JMXConnectorServerFactory;
import javax.management.remote.JMXServiceURL;
import javax.management.remote.rmi.RMIConnectorServer;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.RMIServerSocketFactory;
import java.rmi.server.UnicastRemoteObject;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;


/* ------------------------------------------------------------ */
/**
 * AbstractLifeCycle wrapper for JMXConnector Server
 */
public class ConnectorServer extends AbstractLifeCycle
{
    private static final Logger LOG = Log.getLogger(ConnectorServer.class);

    JMXConnectorServer _connectorServer;
    Registry _registry;

    /* ------------------------------------------------------------ */
    /**
     * Constructs connector server
     *
     * @param serviceURL the address of the new connector server.
     * The actual address of the new connector server, as returned
     * by its getAddress method, will not necessarily be exactly the same.
     * @param name object name string to be assigned to connector server bean
     * @throws Exception if unable to setup connector server
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
     * @param svcUrl the address of the new connector server.
     * The actual address of the new connector server, as returned
     * by its getAddress method, will not necessarily be exactly the same.
     * @param environment  a set of attributes to control the new connector
     * server's behavior. This parameter can be null. Keys in this map must
     * be Strings. The appropriate type of each associated value depends on
     * the attribute. The contents of environment are not changed by this call.
     * @param name object name string to be assigned to connector server bean
     * @throws Exception if unable to create connector server
     */
    public ConnectorServer(JMXServiceURL svcUrl, Map<String,?> environment, String name)
         throws Exception
    {
        String urlPath = svcUrl.getURLPath();
        int idx = urlPath.indexOf("rmi://");
        if (idx > 0)
        {
            String hostPort = urlPath.substring(idx+6, urlPath.indexOf('/', idx+6));
            String regHostPort = startRegistry(hostPort);
            if (regHostPort != null) {
                urlPath = urlPath.replace(hostPort,regHostPort);
                svcUrl = new JMXServiceURL(svcUrl.getProtocol(), svcUrl.getHost(), svcUrl.getPort(), urlPath);
            }
        }
        _connectorServer = createConnectorServer(svcUrl, environment, name);
    }

    private ConnectorServer(JMXServiceURL svcUrl, Map<String, ?> environment, String name, Registry registry)
            throws Exception
    {
        _registry = registry;
        _connectorServer = createConnectorServer(svcUrl, environment, name);
    }

    /**
     * Creates a connector server that only listens to the loopback interface
     *
     * @param port the port of the new connector server.
     * @param name object name string to be assigned to connector server bean
     * @throws Exception if unable to create connector server
     */
    public static ConnectorServer createUsingLoopbackInterface(int port, String name)
            throws Exception
    {
        return createUsingAddress(InetAddress.getLoopbackAddress(), port, name);
    }

    private static ConnectorServer createUsingAddress(InetAddress address, int port, String name)
            throws Exception
    {
        JMXServiceURL serviceUrl = new JMXServiceURL(
                String.format("service:jmx:rmi://%1$s:%2$d/jndi/rmi://%1$s:%2$d/jmxrmi", address.getHostAddress(), port));
        SinglePortRMIServerSocketFactory serverSocketFactory = new SinglePortRMIServerSocketFactory(address);
        Map<String, ?> environment = Collections.singletonMap(
                RMIConnectorServer.RMI_SERVER_SOCKET_FACTORY_ATTRIBUTE,
                serverSocketFactory);
        System.setProperty("java.rmi.server.hostname", address.getHostAddress());
        Registry registry = LocateRegistry.createRegistry(serviceUrl.getPort(), /*csf*/null, serverSocketFactory);
        return new ConnectorServer(serviceUrl, environment, name, registry);
    }

    private static JMXConnectorServer createConnectorServer(JMXServiceURL svcUrl, Map<String, ?> environment, String name)
            throws Exception
    {
        MBeanServer beanServer = ManagementFactory.getPlatformMBeanServer();
        JMXConnectorServer connectorServer = JMXConnectorServerFactory.newJMXConnectorServer(svcUrl, environment, beanServer);
        beanServer.registerMBean(connectorServer, new ObjectName(name));
        return connectorServer;
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

        LOG.info("JMX Remote URL: {}", _connectorServer.getAddress().toString());
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
        stopRegistry();
    }

    /**
     * Check that local RMI registry is used, and ensure it is started. If local RMI registry is being used and not started, start it.
     *
     * @param hostPath
     *            hostname and port number of RMI registry
     * @throws Exception
     */
    private String startRegistry(String hostPath) throws Exception
    {
        HostPort hostPort = new HostPort(hostPath);

        String rmiHost = hostPort.getHost();
        int rmiPort = hostPort.getPort(1099);

        // Verify that local registry is being used
        InetAddress hostAddress = InetAddress.getByName(rmiHost);
        if(hostAddress.isLoopbackAddress())
        {
            if (rmiPort == 0)
            {
                ServerSocket socket = new ServerSocket(0);
                rmiPort = socket.getLocalPort();
                socket.close();
            }
            else
            {
                try
                {
                    // Check if a local registry is already running
                    LocateRegistry.getRegistry(rmiPort).list();
                    return null;
                }
                catch (Exception ex)
                {
                    LOG.ignore(ex);
                }
            }

            _registry = LocateRegistry.createRegistry(rmiPort);
            Thread.sleep(1000);

            rmiHost = HostPort.normalizeHost(InetAddress.getLocalHost().getCanonicalHostName());
            return rmiHost + ':' + Integer.toString(rmiPort);
        }

        return null;
    }

    private void stopRegistry()
    {
        if (_registry != null)
        {
            try
            {
                UnicastRemoteObject.unexportObject(_registry,true);
            }
            catch (Exception ex)
            {
                LOG.ignore(ex);
            }
        }
    }

    private static class SinglePortRMIServerSocketFactory implements RMIServerSocketFactory
    {
        private final InetAddress address;

        public SinglePortRMIServerSocketFactory(InetAddress address)
        {
            this.address = address;
        }

        @Override
        public ServerSocket createServerSocket(int port)
                throws IOException
        {
            return new ServerSocket(port, 0, address);
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            SinglePortRMIServerSocketFactory that = (SinglePortRMIServerSocketFactory) o;
            return Objects.equals(address, that.address);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(address);
        }
    }
}
