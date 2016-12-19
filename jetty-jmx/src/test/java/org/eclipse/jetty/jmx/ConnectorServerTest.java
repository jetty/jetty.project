//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import java.net.InetAddress;
import java.rmi.registry.LocateRegistry;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import javax.management.remote.JMXServiceURL;
import org.junit.After;
import org.junit.Ignore;
import org.junit.Test;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.AnyOf.anyOf;

public class ConnectorServerTest
{

    private ConnectorServer connectorServer;

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
}
