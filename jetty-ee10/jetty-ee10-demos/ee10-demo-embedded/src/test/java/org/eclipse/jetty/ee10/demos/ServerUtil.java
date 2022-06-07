//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.demos;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class ServerUtil
{
    /**
     * Fix the HttpConfiguration entries for securePort after the dynamic ports have been bound.
     *
     * @param server the server to correct.
     */
    public static Map<String, Integer> fixDynamicPortConfigurations(Server server)
    {
        // Fix ports in HttpConfiguration (since we are using dynamic port assignment for this testcase)
        HttpConfiguration plainHttpConfiguration = null;
        HttpConfiguration secureHttpConfiguration = null;
        int plainHttpPort = -1;
        int secureHttpPort = -1;

        for (Connector connector : server.getConnectors())
        {
            if (connector instanceof ServerConnector)
            {
                ServerConnector serverConnector = (ServerConnector)connector;
                SslConnectionFactory sslConnectionFactory = serverConnector.getConnectionFactory(SslConnectionFactory.class);
                HttpConnectionFactory httpConnectionFactory = serverConnector.getConnectionFactory(HttpConnectionFactory.class);
                if (httpConnectionFactory != null)
                {
                    HttpConfiguration configuration = httpConnectionFactory.getHttpConfiguration();
                    if (sslConnectionFactory != null)
                    {
                        secureHttpConfiguration = configuration;
                        secureHttpPort = serverConnector.getLocalPort();
                    }
                    else
                    {
                        plainHttpConfiguration = configuration;
                        plainHttpPort = serverConnector.getLocalPort();
                    }
                }
            }
        }

        assertNotNull(plainHttpConfiguration, "Plain HTTP Configuration");
        assertNotEquals(plainHttpPort, -1, "Dynamic Plain HTTP Port");

        assertNotNull(secureHttpConfiguration, "Secure HTTP Configuration");
        assertNotEquals(secureHttpPort, -1, "Dynamic Secure Port");

        plainHttpConfiguration.setSecurePort(secureHttpPort);
        secureHttpConfiguration.setSecurePort(secureHttpPort);

        Map<String, Integer> ports = new HashMap<>();
        ports.put("plain", plainHttpPort);
        ports.put("secure", secureHttpPort);

        return ports;
    }
}
