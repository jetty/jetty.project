//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.core;

import org.eclipse.jetty.http.HttpHeader;

public final class WebSocketConstants
{
    // Core use Close Status Codes
    public static final int NORMAL = CloseStatus.NORMAL;
    public static final int SHUTDOWN = CloseStatus.SHUTDOWN;
    public static final int PROTOCOL = CloseStatus.PROTOCOL;
    public static final int BAD_DATA = CloseStatus.BAD_DATA;
    public static final int NO_CODE = CloseStatus.NO_CODE;
    public static final int NO_CLOSE = CloseStatus.NO_CLOSE;
    public static final int BAD_PAYLOAD = CloseStatus.BAD_PAYLOAD;
    public static final int POLICY_VIOLATION = CloseStatus.POLICY_VIOLATION;
    public static final int MESSAGE_TOO_LARGE = CloseStatus.MESSAGE_TOO_LARGE;
    public static final int SERVER_ERROR = CloseStatus.SERVER_ERROR;
    public static final int FAILED_TLS_HANDSHAKE = CloseStatus.FAILED_TLS_HANDSHAKE;

    // Request / Response Header Names
    public static final String SEC_WEBSOCKET_EXTENSIONS = HttpHeader.SEC_WEBSOCKET_EXTENSIONS.asString();
    public static final String SEC_WEBSOCKET_PROTOCOL = HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL.asString();
    public static final String SEC_WEBSOCKET_VERSION = HttpHeader.SEC_WEBSOCKET_VERSION.asString();
    public static final String SEC_WEBSOCKET_ACCEPT = HttpHeader.SEC_WEBSOCKET_ACCEPT.asString();
    public static final String SEC_WEBSOCKET_ORIGIN = "Sec-WebSocket-Origin";
    public static final String SEC_WEBSOCKET_KEY = HttpHeader.SEC_WEBSOCKET_KEY.asString();

    // Supported Spec Version
    public static final int SPEC_VERSION = 13;
}

