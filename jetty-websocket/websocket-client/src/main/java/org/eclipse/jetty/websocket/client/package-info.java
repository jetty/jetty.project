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

/**
 * Jetty WebSocket Client API
 * <p>
 * The core class is {@link org.eclipse.jetty.websocket.client.WebSocketClient}, which acts as a central configuration object (for example
 * for {@link org.eclipse.jetty.websocket.client.WebSocketClient#setConnectTimeout(long)}, 
 * {@link org.eclipse.jetty.websocket.client.WebSocketClient#setCookieStore(java.net.CookieStore)}, 
 * etc.) and as a factory for WebSocket {@link org.eclipse.jetty.websocket.api.Session} objects.
 * <p>
 * The <a href="https://tools.ietf.org/html/rfc6455">WebSocket protocol</a> is based on a framing protocol built
 * around an upgraded HTTP connection.  It is primarily focused on the sending of messages (text or binary), with an
 * occasional control frame (close, ping, pong) that this implementation uses.  
 * <p>
 * {@link org.eclipse.jetty.websocket.client.WebSocketClient} holds a number of {@link org.eclipse.jetty.websocket.api.Session}, which in turn
 * is used to manage physical vs virtual connection handling (mux extension).
 */
package org.eclipse.jetty.websocket.client;

