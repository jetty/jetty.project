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

package org.eclipse.jetty.io;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.util.EventListener;

import org.eclipse.jetty.util.component.Container;

/**
 * <p>A {@link Connection} is associated to an {@link EndPoint} so that I/O events
 * happening on the {@link EndPoint} can be processed by the {@link Connection}.</p>
 * <p>A typical implementation of {@link Connection} overrides {@link #onOpen()} to
 * {@link EndPoint#fillInterested(org.eclipse.jetty.util.Callback) set read interest} on the {@link EndPoint},
 * and when the {@link EndPoint} signals read readyness, this {@link Connection} can
 * read bytes from the network and interpret them.</p>
 */
public interface Connection extends Closeable
{
    /**
     * <p>Adds a listener of connection events.</p>
     *
     * @param listener the listener to add
     */
    public void addEventListener(EventListener listener);

    /**
     * <p>Removes a listener of connection events.</p>
     *
     * @param listener the listener to remove
     */
    public void removeEventListener(EventListener listener);

    /**
     * <p>Callback method invoked when this connection is opened.</p>
     * <p>Creators of the connection implementation are responsible for calling this method.</p>
     */
    public void onOpen();

    /**
     * <p>Callback method invoked when this connection is closed.</p>
     * <p>Creators of the connection implementation are responsible for calling this method.</p>
     *
     * @param cause The cause of the close or null for a normal close
     */
    public void onClose(Throwable cause);

    /**
     * @return the {@link EndPoint} associated with this Connection.
     */
    public EndPoint getEndPoint();

    /**
     * <p>Performs a logical close of this connection.</p>
     * <p>For simple connections, this may just mean to delegate the close to the associated
     * {@link EndPoint} but, for example, SSL connections should write the SSL close message
     * before closing the associated {@link EndPoint}.</p>
     */
    @Override
    public void close();

    /**
     * <p>Callback method invoked upon an idle timeout event.</p>
     * <p>Implementations of this method may return true to indicate that the idle timeout
     * handling should proceed normally, typically failing the EndPoint and causing it to
     * be closed.</p>
     * <p>When false is returned, the handling of the idle timeout event is halted
     * immediately and the EndPoint left in the state it was before the idle timeout event.</p>
     *
     * @return true to let the EndPoint handle the idle timeout,
     * false to tell the EndPoint to halt the handling of the idle timeout.
     */
    public boolean onIdleExpired();

    public long getMessagesIn();

    public long getMessagesOut();

    public long getBytesIn();

    public long getBytesOut();

    public long getCreatedTimeStamp();

    /**
     * <p>{@link Connection} implementations implement this interface when they
     * can upgrade from the protocol they speak (for example HTTP/1.1)
     * to a different protocol (e.g. HTTP/2).</p>
     *
     * @see EndPoint#upgrade(Connection)
     * @see UpgradeTo
     */
    public interface UpgradeFrom
    {
        /**
         * <p>Invoked during an {@link EndPoint#upgrade(Connection) upgrade}
         * to produce a buffer containing bytes that have not been consumed by
         * this connection, and that must be consumed by the upgrade-to
         * connection.</p>
         *
         * @return a buffer of unconsumed bytes to pass to the upgrade-to connection.
         * The buffer does not belong to any pool and should be discarded after
         * having consumed its bytes.
         * The returned buffer may be null if there are no unconsumed bytes.
         */
        public ByteBuffer onUpgradeFrom();
    }

    /**
     * <p>{@link Connection} implementations implement this interface when they
     * can be upgraded to the protocol they speak (e.g. HTTP/2)
     * from a different protocol (e.g. HTTP/1.1).</p>
     */
    public interface UpgradeTo
    {
        /**
         * <p>Invoked during an {@link EndPoint#upgrade(Connection) upgrade}
         * to receive a buffer containing bytes that have not been consumed by
         * the upgrade-from connection, and that must be consumed by this
         * connection.</p>
         *
         * @param buffer a non-null buffer of unconsumed bytes received from
         * the upgrade-from connection.
         * The buffer does not belong to any pool and should be discarded after
         * having consumed its bytes.
         */
        public void onUpgradeTo(ByteBuffer buffer);
    }

    /**
     * <p>A Listener for connection events.</p>
     * <p>Listeners can be added to a {@link Connection} to get open and close events.
     * The AbstractConnectionFactory implements a pattern where objects implement
     * this interface that have been added via {@link Container#addBean(Object)} to
     * the Connector or ConnectionFactory are added as listeners to all new connections
     * </p>
     */
    public interface Listener extends EventListener
    {
        public void onOpened(Connection connection);

        public void onClosed(Connection connection);

        public static class Adapter implements Listener
        {
            @Override
            public void onOpened(Connection connection)
            {
            }

            @Override
            public void onClosed(Connection connection)
            {
            }
        }
    }
}
