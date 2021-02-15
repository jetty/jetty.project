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

package org.eclipse.jetty.websocket.jsr356.server;

import java.util.Iterator;
import java.util.ServiceLoader;
import javax.websocket.server.ServerEndpointConfig;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Test the JettyServerEndpointConfigurator impl.
 */
public class JettyServerEndpointConfiguratorTest
{
    @Test
    public void testServiceLoader()
    {
        System.out.printf("Service Name: %s%n", ServerEndpointConfig.Configurator.class.getName());

        ServiceLoader<ServerEndpointConfig.Configurator> loader = ServiceLoader.load(javax.websocket.server.ServerEndpointConfig.Configurator.class);
        assertThat("loader", loader, notNullValue());
        Iterator<ServerEndpointConfig.Configurator> iter = loader.iterator();
        assertThat("loader.iterator", iter, notNullValue());
        assertThat("loader.iterator.hasNext", iter.hasNext(), is(true));

        ServerEndpointConfig.Configurator configr = iter.next();
        assertThat("Configurator", configr, notNullValue());
        assertThat("Configurator type", configr, instanceOf(ContainerDefaultConfigurator.class));
    }
}
