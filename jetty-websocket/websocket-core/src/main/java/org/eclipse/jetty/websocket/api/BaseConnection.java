package org.eclipse.jetty.websocket.api;

import java.net.InetSocketAddress;

/**
 * Base Connection concepts
 */
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
     * @see StatusCode
     * @see #close(int, String)
     */
    void close();

    /**
     * Terminate connection, with status code.
     * <p>
     * Advanced usage: results in an non-blocking async write, then connection close.
     * 
     * @param statusCode
     *            the status code
     * @param reason
     *            the (optional) reason. (can be null for no reason)
     * @see StatusCode
     */
    void close(int statusCode, String reason);

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
