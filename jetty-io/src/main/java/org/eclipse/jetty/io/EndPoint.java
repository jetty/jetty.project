// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses.
// ========================================================================

package org.eclipse.jetty.io;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;



/**
 *
 * A transport EndPoint
 */
public interface EndPoint
{
    /* ------------------------------------------------------------ */
    /**
     * @return The local Inet address to which this <code>EndPoint</code> is bound, or <code>null</code>
     * if this <code>EndPoint</code> does not represent a network connection.
     */
    InetSocketAddress getLocalAddress();

    /* ------------------------------------------------------------ */
    /**
     * @return The remote Inet address to which this <code>EndPoint</code> is bound, or <code>null</code>
     * if this <code>EndPoint</code> does not represent a network connection.
     */
    InetSocketAddress getRemoteAddress();

    /* ------------------------------------------------------------ */
    boolean isOpen();
    
    /* ------------------------------------------------------------ */
    long getCreatedTimeStamp();
    
    /**
     * Shutdown any backing output stream associated with the endpoint
     */
    void shutdownOutput() throws IOException;

    boolean isOutputShutdown();

    /**
     * Close any backing stream associated with the endpoint
     */
    void close() throws IOException;

    /**
     * Fill the passed buffer with data from this endpoint.  The bytes are appended to any 
     * data already in the buffer by writing from the buffers limit up to it's capacity. 
     * The limit is updated to include the filled bytes.  
     * 
     * @param buffer The buffer to fill. The position and limit are modified during the fill. After the 
     * operation, the position is unchanged and the limit is increased to reflect the new data filled.
     * @return an <code>int</code> value indicating the number of bytes
     * filled or -1 if EOF is reached.
     * @throws EofException If input is shutdown or the endpoint is closed.
     */
    int fill(ByteBuffer buffer) throws IOException;


    /**
     * Flush data from the passed header/buffer to this endpoint.  As many bytes as can be consumed 
     * are taken from the header/buffer position up until the buffer limit.  The header/buffers position 
     * is updated to indicate how many bytes have been consumed.  
     * 
     * @return  the number of bytes written
     * @throws EofException If the endpoint is closed or output is shutdown.
     */
    int flush(ByteBuffer... buffer) throws IOException;
    
    /* ------------------------------------------------------------ */
    /**
     * @return The underlying transport object (socket, channel, etc.)
     */
    Object getTransport();

    /* ------------------------------------------------------------ */
    /** Get the max idle time in ms.
     * <p>The max idle time is the time the endpoint can be idle before
     * extraordinary handling takes place.  
     * @return the max idle time in ms or if ms <= 0 implies an infinite timeout
     */
    int getMaxIdleTime();

    /* ------------------------------------------------------------ */
    /** Set the max idle time.
     * @param timeMs the max idle time in MS. Timeout <= 0 implies an infinite timeout
     * @throws IOException if the timeout cannot be set.
     */
    void setMaxIdleTime(int timeMs) throws IOException;
    


}
