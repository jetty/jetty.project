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


/**
 *
 * A transport EndPoint
 */
public interface EndPoint
{
    /**
     * Shutdown any backing output stream associated with the endpoint
     */
    void shutdownOutput() throws IOException;

    boolean isOutputShutdown();

    /**
     * Shutdown any backing input stream associated with the endpoint
     */
    void shutdownInput() throws IOException;

    boolean isInputShutdown();

    /**
     * Close any backing stream associated with the endpoint
     */
    void close() throws IOException;

    /**
     * Fill the buffer from the current putIndex to it's capacity from whatever
     * byte source is backing the buffer. The putIndex is increased if bytes filled.
     * The buffer may chose to do a compact before filling.
     * @return an <code>int</code> value indicating the number of bytes
     * filled or -1 if EOF is reached.
     * @throws EofException If input is shutdown or the endpoint is closed.
     */
    int fill(Buffer buffer) throws IOException;


    /**
     * Flush the buffer from the current getIndex to it's putIndex using whatever byte
     * sink is backing the buffer. The getIndex is updated with the number of bytes flushed.
     * Any mark set is cleared.
     * If the entire contents of the buffer are flushed, then an implicit empty() is done.
     *
     * @param buffer The buffer to flush. This buffers getIndex is updated.
     * @return  the number of bytes written
     * @throws EofException If the endpoint is closed or output is shutdown.
     */
    int flush(Buffer buffer) throws IOException;

    /**
     * Flush the buffer from the current getIndex to it's putIndex using whatever byte
     * sink is backing the buffer. The getIndex is updated with the number of bytes flushed.
     * Any mark set is cleared.
     * If the entire contents of the buffer are flushed, then an implicit empty() is done.
     * The passed header/trailer buffers are written before/after the contents of this buffer. This may be done
     * either as gather writes, as a poke into this buffer or as several writes. The implementation is free to
     * select the optimal mechanism.
     * @param header A buffer to write before flushing this buffer. This buffers getIndex is updated.
     * @param buffer The buffer to flush. This buffers getIndex is updated.
     * @param trailer A buffer to write after flushing this buffer. This buffers getIndex is updated.
     * @return the total number of bytes written.
     */
    int flush(Buffer header, Buffer buffer, Buffer trailer) throws IOException;


    /* ------------------------------------------------------------ */
    /**
     * @return The local IP address to which this <code>EndPoint</code> is bound, or <code>null</code>
     * if this <code>EndPoint</code> does not represent a network connection.
     */
    public String getLocalAddr();

    /* ------------------------------------------------------------ */
    /**
     * @return The local host name to which this <code>EndPoint</code> is bound, or <code>null</code>
     * if this <code>EndPoint</code> does not represent a network connection.
     */
    public String getLocalHost();

    /* ------------------------------------------------------------ */
    /**
     * @return The local port number on which this <code>EndPoint</code> is listening, or <code>0</code>
     * if this <code>EndPoint</code> does not represent a network connection.
     */
    public int getLocalPort();

    /* ------------------------------------------------------------ */
    /**
     * @return The remote IP address to which this <code>EndPoint</code> is connected, or <code>null</code>
     * if this <code>EndPoint</code> does not represent a network connection.
     */
    public String getRemoteAddr();

    /* ------------------------------------------------------------ */
    /**
     * @return The host name of the remote machine to which this <code>EndPoint</code> is connected, or <code>null</code>
     * if this <code>EndPoint</code> does not represent a network connection.
     */
    public String getRemoteHost();

    /* ------------------------------------------------------------ */
    /**
     * @return The remote port number to which this <code>EndPoint</code> is connected, or <code>0</code>
     * if this <code>EndPoint</code> does not represent a network connection.
     */
    public int getRemotePort();

    /* ------------------------------------------------------------ */
    public boolean isBlocking();

    /* ------------------------------------------------------------ */
    public boolean blockReadable(long millisecs) throws IOException;

    /* ------------------------------------------------------------ */
    public boolean blockWritable(long millisecs) throws IOException;

    /* ------------------------------------------------------------ */
    public boolean isOpen();

    /* ------------------------------------------------------------ */
    /**
     * @return The underlying transport object (socket, channel, etc.)
     */
    public Object getTransport();

    /* ------------------------------------------------------------ */
    /** Flush any buffered output.
     * May fail to write all data if endpoint is non-blocking
     * @throws EofException If the endpoint is closed or output is shutdown.
     */
    public void flush() throws IOException;

    /* ------------------------------------------------------------ */
    /** Get the max idle time in ms.
     * <p>The max idle time is the time the endpoint can be idle before
     * extraordinary handling takes place.  This loosely corresponds to
     * the {@link java.net.Socket#getSoTimeout()} for blocking connections,
     * but {@link AsyncEndPoint} implementations must use other mechanisms
     * to implement the max idle time.
     * @return the max idle time in ms or if ms <= 0 implies an infinite timeout
     */
    public int getMaxIdleTime();

    /* ------------------------------------------------------------ */
    /** Set the max idle time.
     * @param timeMs the max idle time in MS. Timeout <= 0 implies an infinite timeout
     * @throws IOException if the timeout cannot be set.
     */
    public void setMaxIdleTime(int timeMs) throws IOException;



}
