//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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

    /**
     * The default maximum size of a binary message that can be received.
     */
    public static final int DEFAULT_MAX_BINARY_MESSAGE_SIZE = 64 * 1024;

    /**
     * The default maximum size of a text message that can be received.
     */
    public static final int DEFAULT_MAX_TEXT_MESSAGE_SIZE = 64 * 1024;

    /**
     * The default maximum payload size of any WebSocket frame that can be received.
     */
    public static final int DEFAULT_MAX_FRAME_SIZE = 64 * 1024;

    /**
     * The default input buffer size used to read from network/transport layer.
     */
    public static final int DEFAULT_INPUT_BUFFER_SIZE = 4 * 1024;

    /**
     * The default output buffer size used to write to the network/transport layer.
     */
    public static final int DEFAULT_OUTPUT_BUFFER_SIZE = 4 * 1024;

    /**
     * The default maximum number of data frames allowed to be waiting to be sent at any one time.
     */
    public static final int DEFAULT_MAX_OUTGOING_FRAMES = -1;

    /**
     * Whether frames are automatically fragmented to respect the maximum frame size.
     */
    public static final boolean DEFAULT_AUTO_FRAGMENT = true;

    /**
     * The default duration that a websocket connection may be idle before being closed by the implementation.
     */
    public static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofSeconds(30);

    /**
     * The default maximum time a frame may be waiting to be sent.
     */
    @Deprecated(since = "12.0.21", forRemoval = true)
    public static final Duration DEFAULT_WRITE_TIMEOUT = Duration.ZERO;

    // Attributes for storing API requests as attributes on the base jetty-core request.
    public static final String WEBSOCKET_WRAPPED_REQUEST_ATTRIBUTE = "org.eclipse.jetty.websocket.wrappedRequest";
    public static final String WEBSOCKET_WRAPPED_RESPONSE_ATTRIBUTE = "org.eclipse.jetty.websocket.wrappedResponse";

    /**
     * <p>Globally Unique Identifier for use in WebSocket handshake within {@code Sec-WebSocket-Accept}
     * and <code>Sec-WebSocket-Key</code> HTTP headers.</p>
     * <p>See <a href="https://tools.ietf.org/html/rfc6455#section-1.3">Opening Handshake (Section 1.3)</a>.</p>
     */
    public static final byte[] MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11".getBytes(StandardCharsets.ISO_8859_1);

    private WebSocketConstants()
    {
    }
}
