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

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.RMIServerSocketFactory;
import java.rmi.server.UnicastRemoteObject;
import java.util.HashMap;
import java.util.Map;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.remote.JMXConnectorServer;
import javax.management.remote.JMXConnectorServerFactory;
import javax.management.remote.JMXServiceURL;
import javax.management.remote.rmi.RMIConnectorServer;

import org.eclipse.jetty.util.HostPort;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.ShutdownThread;

/**
 * AbstractLifeCycle wrapper for JMXConnectorServer
 */
public class ConnectorServer extends AbstractLifeCycle
{
    private static final Logger LOG = Log.getLogger(ConnectorServer.class);

    private JMXServiceURL _jmxURL;
    private final Map<String, Object> _environment;
    private final String _objectName;
    private String _registryHost;
    private int _registryPort;
    private String _rmiHost;
    private int _rmiPort;
    private JMXConnectorServer _connectorServer;
    private Registry _registry;

    /**
     * Constructs a ConnectorServer
     *
     * @param serviceURL the address of the new ConnectorServer
     * @param name       object name string to be assigned to ConnectorServer bean
     */
    public ConnectorServer(JMXServiceURL serviceURL, String name)
    {
        this(serviceURL, null, name);
    }

    /**
     * Constructs a ConnectorServer
     *
     * @param svcUrl      the address of the new ConnectorServer
     * @param environment a set of attributes to control the new ConnectorServer's behavior.
     *                    This parameter can be null. Keys in this map must
     *                    be Strings. The appropriate type of each associated value depends on
     *                    the attribute. The contents of environment are not changed by this call.
     * @param name        object name string to be assigned to ConnectorServer bean
     */
    public ConnectorServer(JMXServiceURL svcUrl, Map<String, ?> environment, String name)
    {
        this._jmxURL = svcUrl;
        this._environment = environment == null ? new HashMap<>() : new HashMap<>(environment);
        this._objectName = name;
    }

    public JMXServiceURL getAddress()
    {
        return _jmxURL;
    }

    @Override
    public void doStart() throws Exception
    {
        boolean rmi = "rmi".equals(_jmxURL.getProtocol());
        if (rmi)
        {
            if (!_environment.containsKey(RMIConnectorServer.RMI_SERVER_SOCKET_FACTORY_ATTRIBUTE))
                _environment.put(RMIConnectorServer.RMI_SERVER_SOCKET_FACTORY_ATTRIBUTE, new JMXRMIServerSocketFactory(false));
        }

        String urlPath = _jmxURL.getURLPath();
        String jndiRMI = "/jndi/rmi://";
        boolean registry = urlPath.startsWith(jndiRMI);
        if (registry)
        {
            int startIndex = jndiRMI.length();
            int endIndex = urlPath.indexOf('/', startIndex);
            HostPort hostPort = new HostPort(urlPath.substring(startIndex, endIndex));
            _registryHost = hostPort.getHost();
            startRegistry(hostPort);
            urlPath = jndiRMI + _registryHost + ":" + _registryPort + urlPath.substring(endIndex);
            // Rebuild JMXServiceURL to use it for the creation of the JMXConnectorServer.
            _jmxURL = new JMXServiceURL(_jmxURL.getProtocol(), _jmxURL.getHost(), _jmxURL.getPort(), urlPath);
        }

        MBeanServer mbeanServer = ManagementFactory.getPlatformMBeanServer();
        _connectorServer = JMXConnectorServerFactory.newJMXConnectorServer(_jmxURL, _environment, mbeanServer);
        mbeanServer.registerMBean(_connectorServer, new ObjectName(_objectName));
        _connectorServer.start();
        ShutdownThread.register(0, this);

        _jmxURL = new JMXServiceURL(_jmxURL.getProtocol(),
                _rmiHost != null ? _rmiHost : _jmxURL.getHost(),
                _rmiPort > 0 ? _rmiPort : _jmxURL.getPort(),
                urlPath);

        LOG.info("JMX Remote URL: {}", _jmxURL);
    }

    @Override
    public void doStop() throws Exception
    {
        ShutdownThread.deregister(this);
        _connectorServer.stop();
        MBeanServer mbeanServer = ManagementFactory.getPlatformMBeanServer();
        mbeanServer.unregisterMBean(new ObjectName(_objectName));
        stopRegistry();
    }

    private void startRegistry(HostPort hostPort) throws Exception
    {
        String host = hostPort.getHost();
        int port = hostPort.getPort(1099);

        try
        {
            // Check if a local registry is already running.
            LocateRegistry.getRegistry(host, port).list();
            return;
        }
        catch (Throwable ex)
        {
            LOG.ignore(ex);
        }

        _registry = LocateRegistry.createRegistry(port, null, new JMXRMIServerSocketFactory(true));
    }

    private void stopRegistry()
    {
        if (_registry != null)
        {
            try
            {
                UnicastRemoteObject.unexportObject(_registry, true);
            }
            catch (Exception ex)
            {
                LOG.ignore(ex);
            }
            finally
            {
                _registry = null;
            }
        }
    }


    private class JMXRMIServerSocketFactory implements RMIServerSocketFactory
    {
        private boolean registry;

        private JMXRMIServerSocketFactory(boolean registry)
        {
            this.registry = registry;
        }

        @Override
        public ServerSocket createServerSocket(int port) throws IOException
        {
            if (registry)
            {
                InetAddress address;
                if (_registryHost == null || _registryHost.isEmpty())
                {
                    _registryHost = InetAddress.getLocalHost().getHostName();
                    address = null;
                }
                else
                {
                    address = InetAddress.getByName(_registryHost);
                }
                ServerSocket server = new ServerSocket();
                server.bind(new InetSocketAddress(address, port));
                _registryPort = server.getLocalPort();
                return server;
            }
            else
            {
                InetAddress address;
                _rmiHost = _jmxURL.getHost();
                if (_rmiHost == null || _rmiHost.isEmpty())
                {
                    _rmiHost = InetAddress.getLocalHost().getHostName();
                    address = null;
                }
                else
                {
                    address = InetAddress.getByName(_rmiHost);
                }
                ServerSocket server = new ServerSocket();
                server.bind(new InetSocketAddress(address, port));
                _rmiPort = server.getLocalPort();
                return server;
            }
        }
    }
}
