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

/**
 * Jetty WebSocket Servlet API
 * <p>
 * How to provide WebSocket servers via the Jetty WebSocket Servlet API:
 * <ol>
 * <li>Create your WebSocket Object</li>
 * <li>Create your WebSocketServlet</li>
 * <li>Register your WebSocket Object with the WebSocketServletFactory</li>
 * <li>Wire up your WebSocketServlet to your web.xml or via Servlet 3.x annotations</li>
 * </ol>
 */
package org.eclipse.jetty.websocket.servlet;

