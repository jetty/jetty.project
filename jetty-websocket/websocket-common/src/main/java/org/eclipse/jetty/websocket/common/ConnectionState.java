//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.common;

import org.eclipse.jetty.websocket.common.io.IOState;
import org.eclipse.jetty.websocket.common.io.IOState.ConnectionStateListener;

/**
 * Connection states as outlined in <a href="https://tools.ietf.org/html/rfc6455">RFC6455</a>.
 */
public enum ConnectionState
{
    /** [RFC] Initial state of a connection, the upgrade request / response is in progress */
    CONNECTING,
    /**
     * [Impl] Intermediate state between CONNECTING and OPEN, used to indicate that a upgrade request/response is successful, but the end-user provided socket's
     * onOpen code has yet to run.
     * <p>
     * This state is to allow the local socket to initiate messages and frames, but to NOT start reading yet.
     */
    CONNECTED,
    /**
     * [RFC] The websocket connection is established and open.
     * <p>
     * This indicates that the Upgrade has succeed, and the end-user provided socket's onOpen code has completed.
     * <p>
     * It is now time to start reading from the remote endpoint.
     */
    OPEN,
    /**
     * [RFC] The websocket closing handshake is started.
     * <p>
     * This can be considered a half-closed state.
     * <p>
     * When receiving this as an event on {@link ConnectionStateListener#onConnectionStateChange(ConnectionState)} a close frame should be sent using
     * the {@link CloseInfo} available from {@link IOState#getCloseInfo()}
     */
    CLOSING,
    /**
     * [RFC] The websocket connection is closed.
     * <p>
     * Connection should be disconnected and no further reads or writes should occur.
     */
    CLOSED;
}
