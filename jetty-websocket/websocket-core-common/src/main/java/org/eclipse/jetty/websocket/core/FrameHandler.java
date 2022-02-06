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

package org.eclipse.jetty.websocket.core;

import org.eclipse.jetty.util.Callback;

/**
 * Interface for local WebSocket Endpoint Frame handling.
 *
 * <p>
 * This is the receiver of Parsed Frames.  It is implemented by the Application (or Application API layer or Framework)
 * as the primary API to/from the Core websocket implementation.   The instance to be used for each websocket connection
 * is instantiated by the application, either:
 * </p>
 * <ul>
 * <li>On the server, the application layer must provide a {@code org.eclipse.jetty.websocket.core.server.WebSocketNegotiator} instance
 * to negotiate and accept websocket connections, which will return the FrameHandler instance to use from
 * {@code org.eclipse.jetty.websocket.core.server.WebSocketNegotiator#negotiate(Negotiation)}.</li>
 * <li>On the client, the application returns the FrameHandler instance to user from the {@code ClientUpgradeRequest}
 * instance that it passes to the {@code org.eclipse.jetty.websocket.core.client.WebSocketCoreClient#connect(ClientUpgradeRequest)} method/</li>
 * </ul>
 * <p>
 * Once instantiated the FrameHandler follows is used as follows:
 * </p>
 * <ul>
 * <li>The {@link #onOpen(CoreSession, Callback)} method is called when negotiation of the connection is completed. The passed {@link CoreSession} instance is used
 * to obtain information about the connection and to send frames</li>
 * <li>Every data and control frame received is passed to {@link #onFrame(Frame, Callback)}.</li>
 * <li>Received Control Frames that require a response (eg Ping, Close) are first passed to the {@link #onFrame(Frame, Callback)} to give the
 * Application an opportunity to send the response itself. If an appropriate response has not been sent when the callback passed is completed, then a
 * response will be generated.</li>
 * <li>If an error is detected or received, then {@link #onError(Throwable, Callback)} will be called to inform the application of the cause of the problem.
 * The connection will then be closed or aborted and the {@link #onClosed(CloseStatus, Callback)} method called.</li>
 * <li>The {@link #onClosed(CloseStatus, Callback)} method is always called once a websocket connection is terminated, either gracefully or not. The error code
 * will indicate the nature of the close.</li>
 * </ul>
 */
public interface FrameHandler extends IncomingFrames
{
    /**
     * Async notification that Connection is being opened.
     * <p>
     * FrameHandler can write during this call, but can not receive frames until the callback is succeeded.
     * </p>
     * <p>
     * If the FrameHandler succeeds the callback we transition to OPEN state and can now receive frames if
     * not demanding, or can now call {@link CoreSession#demand(long)} to receive frames if demanding.
     * If the FrameHandler fails the callback a close frame will be sent with {@link CloseStatus#SERVER_ERROR} and
     * the connection will be closed. <br>
     * </p>
     *
     * @param coreSession the session associated with this connection.
     * @param callback the callback to indicate success in processing (or failure)
     */
    void onOpen(CoreSession coreSession, Callback callback);

    /**
     * Receiver of all Frames.
     * This method will never be called in parallel for the same session and will be called
     * sequentially to satisfy all outstanding demand signaled by calls to
     * {@link CoreSession#demand(long)}.
     * Control and Data frames are passed to this method.
     * Close frames may be responded to by the handler, but if an appropriate close response is not
     * sent once the callback is succeeded, then a response close will be generated and sent.
     *
     * @param frame the raw frame
     * @param callback the callback to indicate success in processing frame (or failure)
     */
    void onFrame(Frame frame, Callback callback);

    /**
     * An error has occurred or been detected in websocket-core and being reported to FrameHandler.
     * A call to onError will be followed by a call to {@link #onClosed(CloseStatus, Callback)} giving the close status
     * derived from the error. This will not be called more than once, {@link #onClosed(CloseStatus, Callback)}
     * will be called on the callback completion.
     *
     * @param cause the reason for the error
     * @param callback the callback to indicate success in processing (or failure)
     */
    void onError(Throwable cause, Callback callback);

    /**
     * This is the Close Handshake Complete event.
     * <p>
     * The connection is now closed, no reading or writing is possible anymore.
     * Implementations of FrameHandler can cleanup their resources for this connection now.
     * This method will be called only once.
     * </p>
     *
     * @param closeStatus the close status received from remote, or in the case of abnormal closure from local.
     * @param callback the callback to indicate success in processing (or failure)
     */
    void onClosed(CloseStatus closeStatus, Callback callback);

    /**
     * Does the FrameHandler manage it's own demand?
     *
     * @return true iff the FrameHandler will manage its own flow control by calling {@link CoreSession#demand(long)} when it
     * is willing to receive new Frames.  Otherwise the demand will be managed by an automatic call to demand(1) after every
     * succeeded callback passed to {@link #onFrame(Frame, Callback)}.
     */
    default boolean isDemanding()
    {
        return false;
    }
}
