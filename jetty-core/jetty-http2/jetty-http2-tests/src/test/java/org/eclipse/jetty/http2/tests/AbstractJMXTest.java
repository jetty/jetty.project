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

package org.eclipse.jetty.http2.tests;

import java.lang.management.ManagementFactory;

import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.server.ConnectionFactory;

public class AbstractJMXTest extends AbstractTest
{
    protected MBeanContainer serverMBeanContainer;
    protected MBeanContainer clientMBeanContainer;

    @Override
    protected void prepareServer(ConnectionFactory... connectionFactories)
    {
        super.prepareServer(connectionFactories);
        serverMBeanContainer = new MBeanContainer(ManagementFactory.getPlatformMBeanServer());
        server.addBean(serverMBeanContainer, true);
    }

    @Override
    protected void prepareClient()
    {
        super.prepareClient();
        clientMBeanContainer = new MBeanContainer(ManagementFactory.getPlatformMBeanServer());
        httpClient.addBean(clientMBeanContainer, true);
    }
}
