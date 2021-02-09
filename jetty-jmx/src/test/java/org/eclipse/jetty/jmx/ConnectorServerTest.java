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

import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import javax.rmi.ssl.SslRMIClientSocketFactory;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Running the tests of this class in the same JVM results often in
 * <pre>
 * Caused by: java.rmi.NoSuchObjectException: no such object in table
 *     at sun.rmi.transport.StreamRemoteCall.exceptionReceivedFromServer(StreamRemoteCall.java:276)
 *     at sun.rmi.transport.StreamRemoteCall.executeCall(StreamRemoteCall.java:253)
 *     at sun.rmi.server.UnicastRef.invoke(UnicastRef.java:379)
 *     at sun.rmi.registry.RegistryImpl_Stub.bind(Unknown Source)
 * </pre>
 * Running each test method in a forked JVM makes these tests all pass,
 * therefore the issue is likely caused by use of stale stubs cached by the JDK.
 */
@Disabled
public class ConnectorServerTest
{
    private String objectName = "org.eclipse.jetty:name=rmiconnectorserver";
    private ConnectorServer connectorServer;

    @AfterEach
    public void tearDown() throws Exception
    {
        if (connectorServer != null)
            connectorServer.stop();
    }

    @Test
    public void testAddressAfterStart() throws Exception
    {
        connectorServer = new ConnectorServer(new JMXServiceURL("service:jmx:rmi:///jndi/rmi:///jmxrmi"), objectName);
        connectorServer.start();

        JMXServiceURL address = connectorServer.getAddress();
        assertTrue(address.toString().matches("service:jmx:rmi://[^:]+:\\d+/jndi/rmi://[^:]+:\\d+/jmxrmi"));
    }

    @Test
    public void testNoRegistryHostBindsToHost() throws Exception
    {
        connectorServer = new ConnectorServer(new JMXServiceURL("service:jmx:rmi:///jndi/rmi:///jmxrmi"), objectName);
        connectorServer.start();

        // Verify that I can connect to the RMI registry using a non-loopback address.
        new Socket(InetAddress.getLocalHost(), 1099).close();
        assertThrows(ConnectException.class, () ->
        {
            // Verify that I cannot connect to the RMI registry using the loopback address.
            new Socket(InetAddress.getLoopbackAddress(), 1099).close();
        });
    }

    @Test
    public void testNoRegistryHostNonDefaultRegistryPort() throws Exception
    {
        ServerSocket serverSocket = new ServerSocket(0);
        int registryPort = serverSocket.getLocalPort();
        serverSocket.close();
        connectorServer = new ConnectorServer(new JMXServiceURL("service:jmx:rmi:///jndi/rmi://:" + registryPort + "/jmxrmi"), objectName);
        connectorServer.start();

        // Verify that I can connect to the RMI registry using a non-loopback address.
        new Socket(InetAddress.getLocalHost(), registryPort).close();
        assertThrows(ConnectException.class, () ->
        {
            // Verify that I cannot connect to the RMI registry using the loopback address.
            new Socket(InetAddress.getLoopbackAddress(), registryPort).close();
        });
    }

    @Test
    public void testAnyRegistryHostBindsToAny() throws Exception
    {
        ServerSocket serverSocket = new ServerSocket(0);
        int registryPort = serverSocket.getLocalPort();
        serverSocket.close();
        connectorServer = new ConnectorServer(new JMXServiceURL("service:jmx:rmi:///jndi/rmi://0.0.0.0:" + registryPort + "/jmxrmi"), objectName);
        connectorServer.start();

        // Verify that I can connect to the RMI registry using a non-loopback address.
        new Socket(InetAddress.getLocalHost(), registryPort).close();
        // Verify that I can connect to the RMI registry using the loopback address.
        new Socket(InetAddress.getLoopbackAddress(), registryPort).close();
    }

    @Test
    public void testLocalhostRegistryBindsToLoopback() throws Exception
    {
        connectorServer = new ConnectorServer(new JMXServiceURL("service:jmx:rmi:///jndi/rmi://localhost:1099/jmxrmi"), objectName);
        connectorServer.start();

        InetAddress localHost = InetAddress.getLocalHost();
        if (!localHost.isLoopbackAddress())
        {
            assertThrows(ConnectException.class, () ->
            {
                // Verify that I cannot connect to the RMIRegistry using a non-loopback address.
                new Socket(localHost, 1099);
            });
        }

        InetAddress loopback = InetAddress.getLoopbackAddress();
        new Socket(loopback, 1099).close();
    }

    @Test
    public void testNoRMIHostBindsToHost() throws Exception
    {
        connectorServer = new ConnectorServer(new JMXServiceURL("service:jmx:rmi:///jndi/rmi:///jmxrmi"), objectName);
        connectorServer.start();

        // Verify that I can connect to the RMI server using a non-loopback address.
        new Socket(InetAddress.getLocalHost(), connectorServer.getAddress().getPort()).close();
        assertThrows(ConnectException.class, () ->
        {
            // Verify that I cannot connect to the RMI server using the loopback address.
            new Socket(InetAddress.getLoopbackAddress(), connectorServer.getAddress().getPort()).close();
        });
    }

    @Test
    public void testAnyRMIHostBindsToAny() throws Exception
    {
        connectorServer = new ConnectorServer(new JMXServiceURL("service:jmx:rmi://0.0.0.0/jndi/rmi:///jmxrmi"), objectName);
        connectorServer.start();

        // Verify that I can connect to the RMI server using a non-loopback address.
        new Socket(InetAddress.getLocalHost(), connectorServer.getAddress().getPort()).close();
        // Verify that I can connect to the RMI server using the loopback address.
        new Socket(InetAddress.getLoopbackAddress(), connectorServer.getAddress().getPort()).close();
    }

    @Test
    public void testLocalhostRMIBindsToLoopback() throws Exception
    {
        connectorServer = new ConnectorServer(new JMXServiceURL("service:jmx:rmi://localhost/jndi/rmi://localhost:1099/jmxrmi"), objectName);
        connectorServer.start();
        JMXServiceURL address = connectorServer.getAddress();

        InetAddress localHost = InetAddress.getLocalHost();
        if (!localHost.isLoopbackAddress())
        {
            assertThrows(ConnectException.class, () ->
            {
                // Verify that I cannot connect to the RMIRegistry using a non-loopback address.
                new Socket(localHost, address.getPort());
            });
        }

        InetAddress loopback = InetAddress.getLoopbackAddress();
        new Socket(loopback, address.getPort()).close();
    }

    @Test
    public void testRMIServerPort() throws Exception
    {
        ServerSocket server = new ServerSocket(0);
        int port = server.getLocalPort();
        server.close();

        connectorServer = new ConnectorServer(new JMXServiceURL("service:jmx:rmi://localhost:" + port + "/jndi/rmi:///jmxrmi"), objectName);
        connectorServer.start();

        JMXServiceURL address = connectorServer.getAddress();
        assertEquals(port, address.getPort());

        InetAddress loopback = InetAddress.getLoopbackAddress();
        new Socket(loopback, port).close();
    }

    @Test
    public void testRMIServerAndRMIRegistryOnSameHostAndSamePort() throws Exception
    {
        // RMI can multiplex connections on the same address and port for different
        // RMI objects, in this case the RMI registry and the RMI server. In this
        // case, the RMIServerSocketFactory will be invoked only once.
        // The case with different address and same port is already covered by TCP,
        // that can listen to 192.168.0.1:1099 and 127.0.0.1:1099 without problems.

        String host = "localhost";
        ServerSocket serverSocket = new ServerSocket(0);
        int port = serverSocket.getLocalPort();
        serverSocket.close();

        connectorServer = new ConnectorServer(new JMXServiceURL("rmi", host, port, "/jndi/rmi://" + host + ":" + port + "/jmxrmi"), objectName);
        connectorServer.start();

        JMXServiceURL address = connectorServer.getAddress();
        assertEquals(port, address.getPort());
    }

    @Test
    public void testJMXOverTLS() throws Exception
    {
        SslContextFactory sslContextFactory = new SslContextFactory.Server();
        String keyStorePath = MavenTestingUtils.getTestResourcePath("keystore.jks").toString();
        String keyStorePassword = "storepwd";
        sslContextFactory.setKeyStorePath(keyStorePath);
        sslContextFactory.setKeyStorePassword(keyStorePassword);
        sslContextFactory.start();

        // The RMIClientSocketFactory is stored within the RMI stub.
        // When using TLS, the stub is deserialized in a possibly different
        // JVM that does not have access to the server keystore, and there
        // is no way to provide TLS configuration during the deserialization
        // of the stub. Therefore the client must provide system properties
        // to specify the TLS configuration. For this test it needs the
        // trustStore because the server certificate is self-signed.
        // The server needs to contact the RMI registry and therefore also
        // needs these system properties.
        System.setProperty("javax.net.ssl.trustStore", keyStorePath);
        System.setProperty("javax.net.ssl.trustStorePassword", keyStorePassword);

        connectorServer = new ConnectorServer(new JMXServiceURL("rmi", null, 1100, "/jndi/rmi://localhost:1100/jmxrmi"), null, objectName, sslContextFactory);
        connectorServer.start();

        // The client needs to talk TLS to the RMI registry to download
        // the RMI server stub, and this is independent from JMX.
        // The RMI server stub then contains the SslRMIClientSocketFactory
        // needed to talk to the RMI server.
        Map<String, Object> clientEnv = new HashMap<>();
        clientEnv.put(ConnectorServer.RMI_REGISTRY_CLIENT_SOCKET_FACTORY_ATTRIBUTE, new SslRMIClientSocketFactory());
        try (JMXConnector client = JMXConnectorFactory.connect(connectorServer.getAddress(), clientEnv))
        {
            client.getMBeanServerConnection().queryNames(null, null);
        }
    }
}
