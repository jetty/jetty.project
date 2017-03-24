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

import org.junit.After;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import javax.management.remote.JMXServiceURL;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.rmi.registry.LocateRegistry;

import static org.hamcrest.core.AnyOf.anyOf;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

public class ConnectorServerTest
{

    private ConnectorServer connectorServer;

    @Rule
    public final ExpectedException expectedException = ExpectedException.none();

    @After
    public void tearDown() throws Exception
    {
        if (connectorServer != null)
        {
            connectorServer.doStop();
        }
    }

    @Test
    public void randomPortTest() throws Exception
    {
        // given
        connectorServer = new ConnectorServer(new JMXServiceURL("service:jmx:rmi:///jndi/rmi://localhost:0/jettytest"),
                "org.eclipse.jetty:name=rmiconnectorserver");
        // if port is not available then the server value is null
        if (connectorServer != null)
        {
            // when
            connectorServer.start();

            // then
            assertThat("Server status must be in started or starting",connectorServer.getState(),
                    anyOf(is(ConnectorServer.STARTED),is(ConnectorServer.STARTING)));
        }
    }

    @Test
    @Ignore // collides on ci server
    public void testConnServerWithRmiDefaultPort() throws Exception
    {
        // given
        LocateRegistry.createRegistry(1099);
        JMXServiceURL serviceURLWithOutPort = new JMXServiceURL("service:jmx:rmi:///jndi/rmi://localhost:1099/jmxrmi");
        connectorServer = new ConnectorServer(serviceURLWithOutPort," domain: key3 = value3");

        // when
        connectorServer.start();

        // then
        assertThat("Server status must be in started or starting",connectorServer.getState(),anyOf(is(ConnectorServer.STARTED),is(ConnectorServer.STARTING)));
    }

    @Test
    public void testConnServerWithRmiRandomPort() throws Exception
    {
        // given
        JMXServiceURL serviceURLWithOutPort = new JMXServiceURL("service:jmx:rmi:///jndi/rmi://localhost:1199/jmxrmi");
        connectorServer = new ConnectorServer(serviceURLWithOutPort," domain: key4 = value4");
        // if port is not available then the server value is null
        if (connectorServer != null)
        {
            // when
            connectorServer.start();
            connectorServer.stop();

            // then
            assertThat("Server status must be in started or starting",connectorServer.getState(),
                    anyOf(is(ConnectorServer.STOPPING),is(ConnectorServer.STOPPED)));
        }
    }

    @Test
    @Ignore
    public void testIsLoopbackAddressWithWrongValue() throws Exception
    {
        // given
        JMXServiceURL serviceURLWithOutPort = new JMXServiceURL("service:jmx:rmi:///jndi/rmi://" + InetAddress.getLocalHost() + ":1199/jmxrmi");

        // when
        connectorServer = new ConnectorServer(serviceURLWithOutPort," domain: key5 = value5");

        // then
        assertNull("As loopback address returns false...registry must be null",connectorServer._registry);
    }

    @Test
    public void connector_server_from_createUsingLoopbackInterface_only_listens_to_loopback_interface()
            throws Exception
    {
        int port = 1199;
        connectorServer = ConnectorServer.createUsingLoopbackInterface(port, "org.eclipse.jetty:name=rmiconnectorserver");
        connectorServer.start();

        assertTrue(new Socket(InetAddress.getLoopbackAddress(), port).isConnected());
        expectedException.expect(IOException.class);
        expectedException.expectMessage("Connection refused (Connection refused)");
        new Socket(InetAddress.getLocalHost(), port);
    }
}
