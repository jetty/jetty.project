//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.core;

import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.extensions.ExtensionConfig;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Interface for local WebSocket Endpoint Frame handling.
 *
 * <p>
 * This is the receiver of Parsed Frames.
 * TODO Document here the close/error lifecycle.  Ie when is onError called?
 * TODO will onclosed always be called? the fact that onReceivedFrame is a called
 * TODO for all frames including control frames... and that app can respond,
 * TODO but if not the core will on itsw behalf of CLOSE and PINGs
 *
 * </p>
 */
public interface FrameHandler extends IncomingFrames
{
    // TODO: have conversation about "throws Exception" vs "throws WebSocketException" vs "throws Throwable" in below signatures.


    /**
     * Connection is being opened.
     * <p>
     *     FrameHandler can write during this call, but will not receive frames until
     *     the onOpen() completes.
     * </p>
     * @param coreSession the channel associated with this connection.
     * @throws Exception if unable to open. TODO: will close the connection (optionally choosing close status code based on WebSocketException type)?
     */
    void onOpen(CoreSession coreSession) throws Exception;

    /**
     * Receiver of all Frames.
     * This method will never be called in parallel for the same session and will be called 
     * sequentially to satisfy all outstanding demand signaled by calls to 
     * {@link CoreSession#demand(long)}.
     * Control and Data frames are passed to this method. 
     * Control frames that require a response (eg PING and CLOSE) may be responded to by the 
     * the handler, but if an appropriate response is not sent once the callback is succeeded, 
     * then a response will be generated and sent.
     * @param frame the raw frame
     * @param callback the callback to indicate success in processing frame (or failure)
     */
    void onReceiveFrame(Frame frame, Callback callback);

    /**
     * This is the Close Handshake Complete event.
     * <p>
     *     The connection is now closed, no reading or writing is possible anymore.
     *     Implementations of FrameHandler can cleanup their resources for this connection now.
     * </p>
     *
     * @param closeStatus the close status received from remote, or in the case of abnormal closure from local.
     */
    void onClosed(CloseStatus closeStatus);

    /**
     * An error has occurred or been detected in websocket-core and being reported to FrameHandler.
     * A call to onError will be followed by a call to {@link #onClosed(CloseStatus)} giving the close status
     * derived from the error.
     *
     * @param cause the reason for the error
     * @throws Exception if unable to process the error.
     */
    void onError(Throwable cause) throws Exception;
    

    /**
     * Does the FrameHandler manage it's own demand?
     * @return true iff the FrameHandler will manage its own flow control by calling {@link CoreSession#demand(long)} when it
     * is willing to receive new Frames.  Otherwise the demand will be managed by an automatic call to demand(1) after every
     * succeeded callback passed to {@link #onReceiveFrame(Frame, Callback)}.
     */
    default boolean isDemanding()
    {
        return false;
    }
    
    
    /**
     * Represents the outgoing Frames.
     */
    interface CoreSession extends OutgoingFrames, Attributes // TODO: want AutoCloseable (easier testing)
    {
        /**
         * The negotiated WebSocket Subprotocol for this channel.
         *
         * @return the negotiated WebSocket Subprotocol for this channel.
         */
        String getSubprotocol();

        // TODO: would like a .fail(Throwable cause) to fail the channel.
        // This should result in a FrameHandler.onError() and/or onClosed()
        // It should be smart about the Exception type (eg: oejwc.CloseException)

        /**
         * The negotiated WebSocket Extension Configurations for this channel.
         *
         * @return the list of Extension Configurations for this channel.
         */
        List<ExtensionConfig> getExtensionConfig();

        /**
         * Issue a harsh abort of the underlying connection.
         * <p>
         * This will terminate the connection, without sending a websocket close frame.
         * No WebSocket Protocol close handshake will be performed.
         * </p>
         * <p>
         * Once called, any read/write activity on the websocket from this point will be indeterminate.
         * This can result in the {@link #onError(Throwable)} event being called indicating any issue that arises.
         * </p>
         * <p>
         * Once the underlying connection has been determined to be closed, the {@link #onClosed(CloseStatus)} event will be called.
         * </p>
         */
        void abort();
        
        /**
         * @return Client or Server behaviour
         */
        WebSocketBehavior getBehavior();

        /**
         * The Local Socket Address for the connection
         * <p>
         *     Do not assume that this will return a {@link InetSocketAddress} in all cases.
         *     Use of various proxies, and even UnixSockets can result a SocketAddress being returned
         *     without supporting {@link InetSocketAddress}
         * </p>
         *
         * @return the SocketAddress for the local connection, or null if not supported by Channel
         */
        SocketAddress getLocalAddress();

        /**
         * The Remote Socket Address for the connection
         * <p>
         *     Do not assume that this will return a {@link InetSocketAddress} in all cases.
         *     Use of various proxies, and even UnixSockets can result a SocketAddress being returned
         *     without supporting {@link InetSocketAddress}
         * </p>
         *
         * @return the SocketAddress for the remote connection, or null if not supported by Channel
         */
        SocketAddress getRemoteAddress();

        /**
         * @return True if the websocket is open outbound
         */
        boolean isOpen();

        // TODO: possible configurable for AsyncSendTimeout (JSR356-ism for async write)

        /**
         * Get the Idle Timeout in the unit provided.
         *
         * @param units the time unit interested in.
         * @return the idle timeout in the unit provided (or -1 for no idle timeout, -2 for unset)
         * TODO: is this how we want to handle infinite timeout?
         */
        long getIdleTimeout(TimeUnit units);

        /**
         * Set the Idle Timeout in the unit provided.
         *
         * @param timeout the timeout duration (or -1 for no idle timeout, -2 for unset)
         * @param units the time unit
         * TODO: is this how we want to handle infinite timeout?
         * TODO: what to do if someone sets a ridiculous timeout? eg: (600,000, DAYS) allow it?
         */
        void setIdleTimeout(long timeout, TimeUnit units);

        /**
         * If using BatchMode.ON or BatchMode.AUTO, trigger a flush of enqueued / batched frames.
         *
         * @param callback the callback to track close frame sent (or failed)
         * TODO: what is the expected reaction to Callback.failed()?
         */
        void flushBatch(Callback callback);

        /**
         * Initiate close handshake, no payload (no declared status code or reason phrase)
         *
         * @param callback the callback to track close frame sent (or failed)
         */
        void close(Callback callback);

        /**
         * Initiate close handshake with provide status code and optional reason phrase.
         *
         * @param statusCode the status code (should be a valid status code that can be sent)
         * @param reason optional reason phrase (will be truncated automatically by implementation to fit within limits of protocol)
         * @param callback the callback to track close frame sent (or failed)
         */
        void close(int statusCode, String reason, Callback callback);
        

        /**
         * Manage flow control by indicating demand for handling Frames.  A call to 
         * {@link FrameHandler#onReceiveFrame(Frame, Callback)} will only be made if a 
         * corresponding demand has been signaled.   It is an error to call this method
         * if {@link FrameHandler#isDemanding()} returns false.
         * @param n The number of frames that can be handled (in sequential calls to 
         * {@link FrameHandler#onReceiveFrame(Frame, Callback)}).  May not be negative.
         */
        void demand(long n);        
    }
}
