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

package org.eclipse.jetty.websocket.core;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class WebSocketConstants
{
    // Supported Spec Version
    public static final int SPEC_VERSION = 13;
    public static final String SPEC_VERSION_STRING = Integer.toString(SPEC_VERSION);

    public static final int DEFAULT_MAX_BINARY_MESSAGE_SIZE = 64 * 1024;
    public static final int DEFAULT_MAX_TEXT_MESSAGE_SIZE = 64 * 1024;
    public static final int DEFAULT_MAX_FRAME_SIZE = 64 * 1024;
    public static final int DEFAULT_INPUT_BUFFER_SIZE = 4 * 1024;
    public static final int DEFAULT_OUTPUT_BUFFER_SIZE = 4 * 1024;
    public static final int DEFAULT_MAX_OUTGOING_FRAMES = -1;
    public static final boolean DEFAULT_AUTO_FRAGMENT = true;
    public static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofSeconds(30);
    public static final Duration DEFAULT_WRITE_TIMEOUT = Duration.ZERO;

    // Attributes for storing API requests as attributes on the base jetty-core request.
    public static final String WEBSOCKET_WRAPPED_REQUEST_ATTRIBUTE = "org.eclipse.jetty.websocket.wrappedRequest";
    public static final String WEBSOCKET_WRAPPED_RESPONSE_ATTRIBUTE = "org.eclipse.jetty.websocket.wrappedResponse";

    /**
     * Globally Unique Identifier for use in WebSocket handshake within {@code Sec-WebSocket-Accept} and <code>Sec-WebSocket-Key</code> http headers.
     * <p>
     * See <a href="https://tools.ietf.org/html/rfc6455#section-1.3">Opening Handshake (Section 1.3)</a>
     */
    public static final byte[] MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11".getBytes(StandardCharsets.ISO_8859_1);

    private WebSocketConstants()
    {
    }
}
