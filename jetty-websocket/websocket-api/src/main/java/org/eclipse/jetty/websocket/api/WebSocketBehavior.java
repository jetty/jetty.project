//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

/**
 * Behavior for how the WebSocket should operate.
 * <p>
 * This dictated by the <a href="https://tools.ietf.org/html/rfc6455">RFC 6455</a> spec in various places, where certain behavior must be performed depending on
 * operation as a <a href="https://tools.ietf.org/html/rfc6455#section-4.1">CLIENT</a> vs a <a href="https://tools.ietf.org/html/rfc6455#section-4.2">SERVER</a>
 */
public enum WebSocketBehavior
{
    CLIENT,
    SERVER
}
