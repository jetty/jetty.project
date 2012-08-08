package org.eclipse.jetty.websocket.api;

import java.io.IOException;
import java.net.InetSocketAddress;

public interface BaseConnection
{
    /**
     * Connection suspend token
     */
    public static interface SuspendToken
    {
        /**
         * Resume a previously suspended connection.
         */
        void resume();
    }

    /**
     * Terminate connection, {@link StatusCode#NORMAL}, without a reason.
     * <p>
     * Basic usage: results in an non-blocking async write, then connection close.
     * 
     * @throws IOException
     *             if unable to send the close frame, or close the connection successfully.
     * @see StatusCode
     * @see #close(int, String)
     */
    void close() throws IOException;

    /**
     * Terminate connection, with status code.
     * <p>
     * Advanced usage: results in an non-blocking async write, then connection close.
     * 
     * @param statusCode
     *            the status code
     * @param reason
     *            the (optional) reason. (can be null for no reason)
     * @throws IOException
     *             if unable to send the close frame, or close the connection successfully.
     * @see StatusCode
     */
    void close(int statusCode, String reason) throws IOException;

    /**
     * Get the remote Address in use for this connection.
     * 
     * @return the remote address if available. (situations like mux extension and proxying makes this information unreliable)
     */
    InetSocketAddress getRemoteAddress();

    /**
     * Simple test to see if connection is open (and not closed)
     * 
     * @return true if connection still open
     */
    boolean isOpen();

    /**
     * Tests if the connection is actively reading.
     * 
     * @return true if connection is actively attempting to read.
     */
    boolean isReading();

    /**
     * Suspend a the incoming read events on the connection.
     * 
     * @return
     */
    SuspendToken suspend();
}
