//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

import java.io.Closeable;
import java.nio.ByteBuffer;

/**
 * <p>A {@link Connection} is associated to an {@link EndPoint} so that I/O events
 * happening on the {@link EndPoint} can be processed by the {@link Connection}.</p>
 * <p>A typical implementation of {@link Connection} overrides {@link #onOpen(ByteBuffer)} to
 * {@link EndPoint#fillInterested(Callback) set read interest} on the {@link EndPoint},
 * and when the {@link EndPoint} signals read readyness, this {@link Connection} can
 * read bytes from the network and interpret them.</p>
 */
public interface Connection extends Closeable
{
    public void addListener(Listener listener);

    public void onOpen();

    /**
     * <p>Callback method invoked when this {@link Connection} is closed.</p>
     * <p>Creators of the connection implementation are responsible for calling this method.</p>
     */
    public void onClose();

    /**
     * @return the {@link EndPoint} associated with this {@link Connection}
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

    public int getMessagesIn();
    public int getMessagesOut();
    public long getBytesIn();
    public long getBytesOut();
    public long getCreatedTimeStamp();
    
    public interface UpgradeFrom extends Connection
    {
        /* ------------------------------------------------------------ */
        /** Take the input buffer from the connection on upgrade.
         * <p>This method is used to take any unconsumed input from
         * a connection during an upgrade.
         * @return A buffer of unconsumed input. The caller must return the buffer
         * to the bufferpool when consumed and this connection must not.
         */
        ByteBuffer onUpgradeFrom();
    }
    
    public interface UpgradeTo extends Connection
    {
        /**
         * <p>Callback method invoked when this {@link Connection} is upgraded.</p>
         * <p>This must be called before {@link #onOpen()}.</p>
         * @param prefilledBuffer An optional buffer that can contain prefilled data. Typically this
         * results from an upgrade of one protocol to the other where the old connection has buffered
         * data destined for the new connection.  The new connection must take ownership of the buffer
         * and is responsible for returning it to the buffer pool
         */
        void onUpgradeTo(ByteBuffer prefilled);
    }
    
    public interface Listener
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
