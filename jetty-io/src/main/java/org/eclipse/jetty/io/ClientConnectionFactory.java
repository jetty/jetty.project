//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * Factory for client-side {@link Connection} instances.
 */
public interface ClientConnectionFactory
{
    /**
     *
     * @param endPoint the {@link org.eclipse.jetty.io.EndPoint} to link the newly created connection to
     * @param context the context data to create the connection
     * @return a new {@link Connection}
     * @throws IOException if the connection cannot be created
     */
    public Connection newConnection(EndPoint endPoint, Map<String, Object> context) throws IOException;

    public static class Helper
    {
        private static Logger LOG = Log.getLogger(Helper.class);

        private Helper()
        {
        }

        /**
         * Replaces the given {@code oldConnection} with the given {@code newConnection} on the
         * {@link EndPoint} associated with {@code oldConnection}, performing connection lifecycle management.
         * <p />
         * The {@code oldConnection} will be closed by invoking {@link org.eclipse.jetty.io.Connection#onClose()}
         * and the {@code newConnection} will be opened by invoking {@link org.eclipse.jetty.io.Connection#onOpen()}.
         * @param oldConnection the old connection to replace
         * @param newConnection the new connection replacement
         */
        public static void replaceConnection(Connection oldConnection, Connection newConnection)
        {
            close(oldConnection);
            oldConnection.getEndPoint().setConnection(newConnection);
            open(newConnection);
        }

        private static void open(Connection connection)
        {
            try
            {
                connection.onOpen();
            }
            catch (Throwable x)
            {
                LOG.debug(x);
            }
        }

        private static void close(Connection connection)
        {
            try
            {
                connection.onClose();
            }
            catch (Throwable x)
            {
                LOG.debug(x);
            }
        }
    }
}
