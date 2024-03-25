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

package org.eclipse.jetty.server;

import java.io.Closeable;
import java.io.IOException;
import java.util.EventListener;

/**
 * <p>A {@link Connector} for TCP/IP network connectors</p>
 */
public interface NetworkConnector extends Connector, Closeable
{
    /**
     * <p>Performs the activities needed to open the network communication
     * (for example, to start accepting incoming network connections).</p>
     * <p>Implementation must be idempotent.</p>
     *
     * @throws IOException if this connector cannot be opened
     * @see #close()
     */
    void open() throws IOException;

    /**
     * <p>Performs the activities needed to close the network communication
     * (for example, to stop accepting network connections).</p>
     * <p>Once a connector has been closed, it cannot be opened again without first
     * calling {@link #stop()} and it will not be active again until a subsequent call to {@link #start()}.</p>
     * <p>Implementation must be idempotent.</p>
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

    /**
     * <p>Receives notifications of the {@link NetworkConnector#open()}
     * and {@link NetworkConnector#close()} events.</p>
     */
    interface Listener extends EventListener
    {
        /**
         * <p>Invoked when the given {@link NetworkConnector} has been opened.</p>
         *
         * @param connector the {@link NetworkConnector} that has been opened
         */
        default void onOpen(NetworkConnector connector)
        {
        }

        /**
         * <p>Invoked when the given {@link NetworkConnector} has been closed.</p>
         *
         * @param connector the {@link NetworkConnector} that has been closed
         */
        default void onClose(NetworkConnector connector)
        {
        }
    }
}
