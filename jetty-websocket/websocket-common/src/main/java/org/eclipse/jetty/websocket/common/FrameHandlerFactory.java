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

package org.eclipse.jetty.websocket.common;

import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;

/**
 * Factory for API's to use for creating API specific FrameHandler instances that
 * websocket-core will utilize.
 * <p>
 *     This is used by both the Server APIs and Client APIs.
 * </p>
 */
public interface FrameHandlerFactory
{
    /**
     * Attempt to create a FrameHandler from the provided websocketPojo.
     *
     * @param websocketPojo the websocket pojo to work with
     * @param policy the policy to use when creating new FrameHandler instances
     * @param handshakeRequest the Upgrade Handshake Request used to create the FrameHandler
     * @param handshakeResponse the Upgrade Handshake Response used to create the FrameHandler
     * @return the API specific FrameHandler, or null if this implementation is unable to create the FrameHandler (allowing another {@link FrameHandlerFactory} to try)
     */
    FrameHandler newFrameHandler(Object websocketPojo, WebSocketPolicy policy, HandshakeRequest handshakeRequest, HandshakeResponse handshakeResponse);
}
