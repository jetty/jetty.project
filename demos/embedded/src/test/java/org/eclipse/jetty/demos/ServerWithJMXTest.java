//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.demos;

import java.util.Optional;
import java.util.Set;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Disabled
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
