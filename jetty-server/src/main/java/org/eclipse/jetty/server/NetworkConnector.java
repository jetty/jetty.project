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

package org.eclipse.jetty.server;

import java.io.Closeable;
import java.io.IOException;

/**
 * <p>A {@link Connector} for TCP/IP network connectors</p>
 */
public interface NetworkConnector extends Connector, Closeable
{
    /**
     * <p>Performs the activities needed to open the network communication
     * (for example, to start accepting incoming network connections).</p>
     *
     * @throws IOException if this connector cannot be opened
     * @see #close()
     */
    void open() throws IOException;

    /**
     * <p>Performs the activities needed to close the network communication
     * (for example, to stop accepting network connections).</p>
     * Once a connector has been closed, it cannot be opened again without first
     * calling {@link #stop()} and it will not be active again until a subsequent call to {@link #start()}
     */
    @Override
    void close();

    /**
     * A Connector may be opened and not started (to reserve a port)
     * or closed and running (to allow graceful shutdown of existing connections)
     *
     * @return True if the connector is Open.
     */
    boolean isOpen();

    /**
     * @return The hostname representing the interface to which
     * this connector will bind, or null for all interfaces.
     */
    String getHost();

    /**
     * @return The configured port for the connector or 0 if any available
     * port may be used.
     */
    int getPort();

    /**
     * @return The actual port the connector is listening on, or
     * -1 if it has not been opened, or -2 if it has been closed.
     */
    int getLocalPort();
}
