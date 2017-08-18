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

public final class WSConstants
{
    // Core use Close Status Codes
    public static final int NORMAL = 1000;
    public static final int SHUTDOWN = 1001;
    public static final int PROTOCOL = 1002;
    public static final int BAD_DATA = 1003;
    public static final int NO_CODE = 1005;
    public static final int NO_CLOSE = 1006;
    public static final int BAD_PAYLOAD = 1007;
    public static final int POLICY_VIOLATION = 1008;
    public static final int MESSAGE_TOO_LARGE = 1009;
    public static final int SERVER_ERROR = 1011;
    public static final int FAILED_TLS_HANDSHAKE = 1015;

    // Request / Response Header Names
    public static final String SEC_WEBSOCKET_EXTENSIONS = "Sec-WebSocket-Extensions";
    public static final String SEC_WEBSOCKET_PROTOCOL = "Sec-WebSocket-Protocol";
    public static final String SEC_WEBSOCKET_VERSION = "Sec-WebSocket-Version";
    public static final String SEC_WEBSOCKET_ACCEPT = "Sec-WebSocket-Accept";
    public static final String SEC_WEBSOCKET_ORIGIN = "Sec-WebSocket-Origin";
    public static final String SEC_WEBSOCKET_KEY = "Sec-WebSocket-Key";

    // Supported Spec Version
    public static final int SPEC_VERSION = 13;
}

