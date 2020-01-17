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

package org.eclipse.jetty.io;

import java.io.IOException;
import java.util.EventListener;
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
            client.getBeans(EventListener.class).forEach(connection::addEventListener);
        return connection;
    }

    /**
     * <p>Wraps another ClientConnectionFactory.</p>
     * <p>This is typically done by protocols that send "preface" bytes with some metadata
     * before other protocols. The metadata could be, for example, proxying information
     * or authentication information.</p>
     */
    interface Decorator
    {
        /**
         * <p>Wraps the given {@code factory}.</p>
         *
         * @param factory the ClientConnectionFactory to wrap
         * @return the wrapping ClientConnectionFactory
         */
        ClientConnectionFactory apply(ClientConnectionFactory factory);
    }

    /**
     * <p>A holder for a list of protocol strings identifying a network protocol
     * (for example {@code ["h2", "h2-17", "h2-16"]}) and a {@link ClientConnectionFactory}
     * that creates connections that speak that network protocol.</p>
     */
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

        /**
         * Tests whether one of the protocols of this class is also present in the given candidates list.
         *
         * @param candidates the candidates to match against
         * @return whether one of the protocols of this class is present in the candidates
         */
        public boolean matches(List<String> candidates)
        {
            return protocols.stream().anyMatch(p -> candidates.stream().anyMatch(c -> c.equalsIgnoreCase(p)));
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x%s", getClass().getSimpleName(), hashCode(), protocols);
        }
    }
}
