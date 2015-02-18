//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.common.scopes;

public interface WebSocketScopeListener
{
    /**
     * A WebSocket Container scope was created / activated
     */
    void onWebSocketContainerActivated(WebSocketContainerScope scope);

    /**
     * A WebSocket Container scope was stopped / deactivated
     */
    void onWebSocketContainerDeactivated(WebSocketContainerScope scope);

    /**
     * A WebSocket Session scope was created / activated
     */
    void onWebSocketSessionActivated(WebSocketSessionScope scope);

    /**
     * A WebSocket Session scope was stopped / deactivated
     */
    void onWebSocketSessionDeactivated(WebSocketSessionScope scope);
}
