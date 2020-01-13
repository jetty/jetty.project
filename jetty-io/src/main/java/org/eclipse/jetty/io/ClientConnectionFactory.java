//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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
import java.util.Map;

import org.eclipse.jetty.util.component.ContainerLifeCycle;

/**
 * Factory for client-side {@link Connection} instances.
 */
public interface ClientConnectionFactory
{
    String CONNECTOR_CONTEXT_KEY = "client.connector";

    /**
     * @param endPoint the {@link org.eclipse.jetty.io.EndPoint} to link the newly created connection to
     * @param context the context data to create the connection
     * @return a new {@link Connection}
     * @throws IOException if the connection cannot be created
     */
    Connection newConnection(EndPoint endPoint, Map<String, Object> context) throws IOException;

    default Connection customize(Connection connection, Map<String, Object> context)
    {
        ContainerLifeCycle connector = (ContainerLifeCycle)context.get(CONNECTOR_CONTEXT_KEY);
        connector.getBeans(Connection.Listener.class).forEach(connection::addListener);
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
}
