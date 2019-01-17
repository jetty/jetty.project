//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.io;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.util.component.ContainerLifeCycle;

/**
 * Factory for client-side {@link Connection} instances.
 */
public interface ClientConnectionFactory
{
    public static final String CLIENT_CONTEXT_KEY = "org.eclipse.jetty.client";

    /**
     * @param endPoint the {@link org.eclipse.jetty.io.EndPoint} to link the newly created connection to
     * @param context the context data to create the connection
     * @return a new {@link Connection}
     * @throws IOException if the connection cannot be created
     */
    public Connection newConnection(EndPoint endPoint, Map<String, Object> context) throws IOException;

    public default Connection customize(Connection connection, Map<String, Object> context)
    {
        ContainerLifeCycle client = (ContainerLifeCycle)context.get(CLIENT_CONTEXT_KEY);
        if (client != null)
            client.getBeans(Connection.Listener.class).forEach(connection::addListener);
        return connection;
    }

    public static class Info
    {
        private final List<String> protocols;
        private final ClientConnectionFactory factory;

        public Info(List<String> protocols, ClientConnectionFactory factory)
        {
            this.protocols = protocols;
            this.factory = factory;
        }

        public List<String> getProtocols()
        {
            return protocols;
        }

        public ClientConnectionFactory getClientConnectionFactory()
        {
            return factory;
        }

        public boolean matches(List<String> candidates)
        {
            return protocols.stream().anyMatch(candidates::contains);
        }
    }
}
