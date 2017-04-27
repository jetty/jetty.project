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

import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

import javax.management.remote.JMXServiceURL;

import org.junit.After;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

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
@Ignore
public class ConnectorServerTest
{
    private String objectName = "org.eclipse.jetty:name=rmiconnectorserver";
    private ConnectorServer connectorServer;

    @After
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
        Assert.assertTrue(address.toString().matches("service:jmx:rmi://[^:]+:\\d+/jndi/rmi://[^:]+:\\d+/jmxrmi"));
    }

    @Test
    public void testNoRegistryHostBindsToAny() throws Exception
    {
        connectorServer = new ConnectorServer(new JMXServiceURL("service:jmx:rmi:///jndi/rmi:///jmxrmi"), objectName);
        connectorServer.start();

        InetAddress localHost = InetAddress.getLocalHost();
        if (!localHost.isLoopbackAddress())
        {
            // Verify that I can connect to the RMIRegistry using a non-loopback address.
            new Socket(localHost, 1099).close();
        }
    }

    @Test
    public void testNoRMIHostBindsToAny() throws Exception
    {
        connectorServer = new ConnectorServer(new JMXServiceURL("service:jmx:rmi:///jndi/rmi:///jmxrmi"), objectName);
        connectorServer.start();

        InetAddress localHost = InetAddress.getLocalHost();
        if (!localHost.isLoopbackAddress())
        {
            // Verify that I can connect to the RMI server using a non-loopback address.
            new Socket(localHost, connectorServer.getAddress().getPort()).close();
        }
    }

    @Test
    public void testLocalhostRegistryBindsToLoopback() throws Exception
    {
        connectorServer = new ConnectorServer(new JMXServiceURL("service:jmx:rmi:///jndi/rmi://localhost:1099/jmxrmi"), objectName);
        connectorServer.start();

        InetAddress localHost = InetAddress.getLocalHost();
        if (!localHost.isLoopbackAddress())
        {
            try
            {
                // Verify that I cannot connect to the RMIRegistry using a non-loopback address.
                new Socket(localHost, 1099);
                Assert.fail();
            }
            catch (ConnectException ignored)
            {
                // Ignored.
            }
        }

        InetAddress loopback = InetAddress.getLoopbackAddress();
        new Socket(loopback, 1099).close();
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
            try
            {
                // Verify that I cannot connect to the RMIRegistry using a non-loopback address.
                new Socket(localHost, address.getPort());
                Assert.fail();
            }
            catch (ConnectException ignored)
            {
                // Ignored.
            }
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
        Assert.assertEquals(port, address.getPort());

        InetAddress loopback = InetAddress.getLoopbackAddress();
        new Socket(loopback, port).close();
    }
}
