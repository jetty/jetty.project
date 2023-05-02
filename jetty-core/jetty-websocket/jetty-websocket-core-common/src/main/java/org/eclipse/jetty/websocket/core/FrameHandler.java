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

package org.eclipse.jetty.websocket.core;

import org.eclipse.jetty.util.Callback;

/**
 * <p>Handles incoming WebSocket frames for a given endpoint.</p>
 * <p>FrameHandler is the receiver of parsed WebSocket frames.
 * It is implemented by application code as the primary API to
 * interact with the WebSocket implementation.</p>
 * <p>The FrameHandler instance to be used for each WebSocket
 * connection is instantiated by the application, either:</p>
 * <ul>
 * <li>On the server, the application must provide a
 * {@code WebSocketNegotiator} to negotiate and accept WebSocket
 * connections, which will return the FrameHandler instance from
 * {@code WebSocketNegotiator#negotiate(Negotiation)}.</li>
 * <li>On the client, the application returns the FrameHandler
 * instance from the {@code CoreClientUpgradeRequest} instance
 * passed to {@code WebSocketCoreClient#connect(ClientUpgradeRequest)}.</li>
 * </ul>
 * <p>Once instantiated the FrameHandler is used as follows:</p>
 * <ul>
 * <li>The {@link #onOpen(CoreSession, Callback)} method is called
 * when negotiation of the connection is completed.
 * The {@link CoreSession} argument is used to configure the connection,
 * to obtain information about the connection, and to send frames</li>
 * <li>Every data and control frame received is passed to
 * {@link #onFrame(Frame, Callback)}.</li>
 * <li>Received control frames that require a response (e.g. PING, CLOSE)
 * are first passed to {@link #onFrame(Frame, Callback)} to give the
 * application an opportunity to respond explicitly. If a response
 * has not been sent when the callback argument is completed, then
 * the implementation will generate a response.</li>
 * <li>If an error is detected or received, then
 * {@link #onError(Throwable, Callback)} will be called to inform
 * the application of the cause of the problem.
 * The connection will then be closed or aborted and then
 * {@link #onClosed(CloseStatus, Callback)} will be called.</li>
 * <li>The {@link #onClosed(CloseStatus, Callback)} method is always
 * called once a WebSocket connection is terminated, either gracefully
 * or not. The error code will indicate the nature of the close.</li>
 * </ul>
 * <p>FrameHandler is responsible to manage the demand for more
 * WebSocket frames, either directly by calling {@link CoreSession#demand(long)}
 * or by delegating the demand management to other components.</p>
 */
public interface FrameHandler extends IncomingFrames
{
    /**
     * <p>Invoked when the WebSocket connection is opened.</p>
     * <p>It is allowed to send WebSocket frames via
     * {@link CoreSession#sendFrame(Frame, Callback, boolean)}.
     * <p>WebSocket frames cannot be received  until a call to
     * {@link CoreSession#demand(long)} is made.</p>
     * <p>If the callback argument is failed, the implementation
     * sends a CLOSE frame with {@link CloseStatus#SERVER_ERROR},
     * and the connection will be closed.</p>
     *
     * @param coreSession the session associated with this connection.
     * @param callback the callback to indicate success or failure of
     * the processing of this event.
     */
    void onOpen(CoreSession coreSession, Callback callback);

    /**
     * <p>Invoked when a WebSocket frame is received.</p>
     * <p>This method will never be called concurrently for the
     * same session; will be called sequentially to satisfy the
     * outstanding demand signaled by calls to
     * {@link CoreSession#demand(long)}.</p>
     * <p>Both control and data frames are passed to this method.</p>
     * <p>CLOSE frames may be responded from this method, but if
     * they are not responded, then the implementation will respond
     * when the callback is completed.</p>
     * <p>The callback argument must be completed to indicate
     * that the buffers associated with the frame can be recycled.</p>
     * <p>Additional WebSocket frames (of any type, including CLOSE
     * frames) cannot be received  until a call to
     * {@link CoreSession#demand(long)} is made.</p>
     *
     * @param frame the WebSocket frame.
     * @param callback the callback to indicate success or failure of
     * the processing of this event.
     */
    void onFrame(Frame frame, Callback callback);

    /**
     * <p>Invoked when an error has occurred or has been detected.</p>
     * <p>A call to this method will be followed by a call to
     * {@link #onClosed(CloseStatus, Callback)} with the close status
     * derived from the error.</p>
     * <p>This method will not be called more than once, {@link #onClosed(CloseStatus, Callback)}
     * will be called on the callback completion.
     *
     * @param cause the error cause
     * @param callback the callback to indicate success or failure of
     * the processing of this event.
     */
    void onError(Throwable cause, Callback callback);

    /**
     * <p>Invoked when a WebSocket close event happened.</p>
     * <p>The WebSocket connection is closed, no reading or writing
     * is possible anymore.</p>
     * <p>Implementations of this method may cleanup resources
     * that have been allocated.</p>
     * <p>This method will not be called more than once.</p>
     *
     * @param closeStatus the close status received from the remote peer,
     * or generated locally in the case of abnormal closures.
     * @param callback the callback to indicate success or failure of
     * the processing of this event.
     */
    void onClosed(CloseStatus closeStatus, Callback callback);
}
