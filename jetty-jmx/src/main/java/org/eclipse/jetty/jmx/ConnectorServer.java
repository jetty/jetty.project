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

package org.eclipse.jetty.jmx;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.UnknownHostException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.RMIClientSocketFactory;
import java.rmi.server.RMIServerSocketFactory;
import java.rmi.server.UnicastRemoteObject;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntConsumer;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.remote.JMXConnectorServer;
import javax.management.remote.JMXConnectorServerFactory;
import javax.management.remote.JMXServiceURL;
import javax.management.remote.rmi.RMIConnectorServer;
import javax.rmi.ssl.SslRMIClientSocketFactory;

import org.eclipse.jetty.util.HostPort;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.ShutdownThread;

/**
 * <p>LifeCycle wrapper for JMXConnectorServer.</p>
 * <p>This class provides the following facilities:</p>
 * <ul>
 * <li>participates in the {@code Server} lifecycle</li>
 * <li>starts the RMI registry if not there already</li>
 * <li>allows to bind the RMI registry and the RMI server to the loopback interface</li>
 * <li>makes it easy to use TLS for the JMX communication</li>
 * </ul>
 */
public class ConnectorServer extends AbstractLifeCycle
{
    public static final String RMI_REGISTRY_CLIENT_SOCKET_FACTORY_ATTRIBUTE = "com.sun.jndi.rmi.factory.socket";
    private static final Logger LOG = Log.getLogger(ConnectorServer.class);

    private JMXServiceURL _jmxURL;
    private final Map<String, Object> _environment;
    private final String _objectName;
    private final SslContextFactory _sslContextFactory;
    private int _registryPort;
    private int _rmiPort;
    private JMXConnectorServer _connectorServer;
    private Registry _registry;

    /**
     * Constructs a ConnectorServer
     *
     * @param serviceURL the address of the new ConnectorServer
     * @param name object name string to be assigned to ConnectorServer bean
     */
    public ConnectorServer(JMXServiceURL serviceURL, String name)
    {
        this(serviceURL, null, name);
    }

    /**
     * Constructs a ConnectorServer
     *
     * @param svcUrl the address of the new ConnectorServer
     * @param environment a set of attributes to control the new ConnectorServer's behavior.
     * This parameter can be null. Keys in this map must
     * be Strings. The appropriate type of each associated value depends on
     * the attribute. The contents of environment are not changed by this call.
     * @param name object name string to be assigned to ConnectorServer bean
     */
    public ConnectorServer(JMXServiceURL svcUrl, Map<String, ?> environment, String name)
    {
        this(svcUrl, environment, name, null);
    }

    public ConnectorServer(JMXServiceURL svcUrl, Map<String, ?> environment, String name, SslContextFactory sslContextFactory)
    {
        this._jmxURL = svcUrl;
        this._environment = environment == null ? new HashMap<>() : new HashMap<>(environment);
        this._objectName = name;
        this._sslContextFactory = sslContextFactory;
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
                _environment.put(RMIConnectorServer.RMI_SERVER_SOCKET_FACTORY_ATTRIBUTE, new JMXRMIServerSocketFactory(_jmxURL.getHost(), port -> _rmiPort = port));
            if (_sslContextFactory != null)
            {
                SslRMIClientSocketFactory csf = new SslRMIClientSocketFactory();
                if (!_environment.containsKey(RMIConnectorServer.RMI_CLIENT_SOCKET_FACTORY_ATTRIBUTE))
                    _environment.put(RMIConnectorServer.RMI_CLIENT_SOCKET_FACTORY_ATTRIBUTE, csf);
                if (!_environment.containsKey(RMI_REGISTRY_CLIENT_SOCKET_FACTORY_ATTRIBUTE))
                    _environment.put(RMI_REGISTRY_CLIENT_SOCKET_FACTORY_ATTRIBUTE, csf);
            }
        }

        String urlPath = _jmxURL.getURLPath();
        String jndiRMI = "/jndi/rmi://";
        if (urlPath.startsWith(jndiRMI))
        {
            int startIndex = jndiRMI.length();
            int endIndex = urlPath.indexOf('/', startIndex);
            HostPort hostPort = new HostPort(urlPath.substring(startIndex, endIndex));
            String registryHost = startRegistry(hostPort);
            // If the RMI registry was already started, use the existing port.
            if (_registryPort == 0)
                _registryPort = hostPort.getPort();
            urlPath = jndiRMI + registryHost + ":" + _registryPort + urlPath.substring(endIndex);
            // Rebuild JMXServiceURL to use it for the creation of the JMXConnectorServer.
            _jmxURL = new JMXServiceURL(_jmxURL.getProtocol(), _jmxURL.getHost(), _jmxURL.getPort(), urlPath);
        }

        MBeanServer mbeanServer = ManagementFactory.getPlatformMBeanServer();
        _connectorServer = JMXConnectorServerFactory.newJMXConnectorServer(_jmxURL, _environment, mbeanServer);
        mbeanServer.registerMBean(_connectorServer, new ObjectName(_objectName));
        _connectorServer.start();
        String rmiHost = normalizeHost(_jmxURL.getHost());
        // If _rmiPort is still zero, it's using the same port as the RMI registry.
        if (_rmiPort == 0)
            _rmiPort = _registryPort;
        _jmxURL = new JMXServiceURL(_jmxURL.getProtocol(), rmiHost, _rmiPort, urlPath);

        ShutdownThread.register(0, this);

        LOG.info("JMX URL: {}", _jmxURL);
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

    private String startRegistry(HostPort hostPort) throws Exception
    {
        String host = hostPort.getHost();
        int port = hostPort.getPort(1099);

        try
        {
            // Check if a local registry is already running.
            LocateRegistry.getRegistry(host, port).list();
            return normalizeHost(host);
        }
        catch (Throwable ex)
        {
            LOG.ignore(ex);
        }

        RMIClientSocketFactory csf = _sslContextFactory == null ? null : new SslRMIClientSocketFactory();
        RMIServerSocketFactory ssf = new JMXRMIServerSocketFactory(host, p -> _registryPort = p);
        _registry = LocateRegistry.createRegistry(port, csf, ssf);

        return normalizeHost(host);
    }

    private String normalizeHost(String host) throws UnknownHostException
    {
        return host == null || host.isEmpty() ? InetAddress.getLocalHost().getHostName() : host;
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
        private final String _host;
        private final IntConsumer _portConsumer;

        private JMXRMIServerSocketFactory(String host, IntConsumer portConsumer)
        {
            this._host = host;
            this._portConsumer = portConsumer;
        }

        @Override
        public ServerSocket createServerSocket(int port) throws IOException
        {
            InetAddress address = _host == null || _host.isEmpty() ? InetAddress.getLocalHost() : InetAddress.getByName(_host);
            ServerSocket server = createServerSocket(address, port);
            _portConsumer.accept(server.getLocalPort());
            return server;
        }

        private ServerSocket createServerSocket(InetAddress address, int port) throws IOException
        {
            // A null address binds to the wildcard address.
            if (_sslContextFactory == null)
            {
                ServerSocket server = new ServerSocket();
                try
                {
                    server.bind(new InetSocketAddress(address, port));
                }
                catch (Throwable e)
                {
                    IO.close(server);
                    throw e;
                }
                return server;
            }
            else
            {
                return _sslContextFactory.newSslServerSocket(address == null ? null : address.getHostName(), port, 0);
            }
        }

        @Override
        public int hashCode()
        {
            return _host != null ? _host.hashCode() : 0;
        }

        @Override
        public boolean equals(Object obj)
        {
            if (this == obj)
                return true;
            if (obj == null || getClass() != obj.getClass())
                return false;
            JMXRMIServerSocketFactory that = (JMXRMIServerSocketFactory)obj;
            return Objects.equals(_host, that._host);
        }
    }
}
