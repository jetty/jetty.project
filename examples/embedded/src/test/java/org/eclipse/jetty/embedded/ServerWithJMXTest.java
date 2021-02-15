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

package org.eclipse.jetty.embedded;

import java.util.Optional;
import java.util.Set;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class ServerWithJMXTest extends AbstractEmbeddedTest
{
    private Server server;

    @BeforeEach
    public void startServer() throws Exception
    {
        server = ServerWithJMX.createServer(0);
        server.start();
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testGetTest() throws Exception
    {
        MBeanContainer mbeanContainer = server.getBean(MBeanContainer.class);
        MBeanServer mbeanServer = mbeanContainer.getMBeanServer();

        String name = "org.eclipse.jetty.jmx:name=rmiconnectorserver,*";
        Set<ObjectName> mbeanNames = mbeanServer.queryNames(ObjectName.getInstance(name), null);
        Optional<ObjectName> rmiConnectorNameOptional = mbeanNames.stream().findFirst();
        assertTrue(rmiConnectorNameOptional.isPresent(), "Has RMI Connector Server");
    }
}
