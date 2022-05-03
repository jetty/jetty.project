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

package org.eclipse.jetty.websocket.api;

/**
 * WebSocket Frame Listener interface for incoming WebSocket frames.
 */
public interface WebSocketFrameListener extends WebSocketConnectionListener
{
    /**
     * A WebSocket frame has been received.
     *
     * @param frame the immutable frame received
     */
    void onWebSocketFrame(Frame frame);
}
