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

import java.io.Closeable;

/**
 * <p>A {@link Connection} is associated to an {@link EndPoint} so that I/O events
 * happening on the {@link EndPoint} can be processed by the {@link Connection}.</p>
 * <p>A typical implementation of {@link Connection} overrides {@link #onOpen()} to
 * {@link EndPoint#fillInterested(Callback) set read interest} on the {@link EndPoint},
 * and when the {@link EndPoint} signals read readyness, this {@link Connection} can
 * read bytes from the network and interpret them.</p>
 */
public interface Connection extends Closeable
{
    public void addListener(Listener listener);

    /**
     * <p>Callback method invoked when this {@link Connection} is opened.</p>
     * <p>Creators of the connection implementation are responsible for calling this method.</p>
     */
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
