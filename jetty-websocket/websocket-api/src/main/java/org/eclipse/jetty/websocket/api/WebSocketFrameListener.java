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

package org.eclipse.jetty.websocket.api;

import org.eclipse.jetty.websocket.api.extensions.Frame;

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
