//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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
 * Some constants for Jetty's WebSocket implementation.
 */
public final class JettyWebSocket
{
    /**
     * Version of the Jetty WebSocket API
     */
    public static final int API_VERSION = 2;
    /**
     * Minimum WebSocket Protocol version supported.
     */
    public static final int PROTOCOL_MINIMUM = 13;
    /**
     * Maximum WebSocket Protocol version supported.
     */
    public static final int PROTOCOL_MAXIMUM = 13;
}
