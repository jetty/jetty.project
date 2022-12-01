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

    /**
     * Max Binary Message Size - The maximum size of a binary message which can be received.
     */
    public static final int DEFAULT_MAX_BINARY_MESSAGE_SIZE = 64 * 1024;

    /**
     * Max Text Message Size - The maximum size of a text message which can be received.
     */
    public static final int DEFAULT_MAX_TEXT_MESSAGE_SIZE = 64 * 1024;

    /**
     * Max Frame Size - The maximum payload size of any WebSocket Frame which can be received.
     */
    public static final int DEFAULT_MAX_FRAME_SIZE = 64 * 1024;

    /**
     * Output Buffer Size - The output (write to network layer) buffer size. This is the raw write operation buffer size and has no relationship to the websocket frame.
     */
    public static final int DEFAULT_INPUT_BUFFER_SIZE = 4 * 1024;

    /**
     * Input Buffer Size - The input (read from network layer) buffer size. This is the raw read operation buffer size, before the parsing of the websocket frames.
     */
    public static final int DEFAULT_OUTPUT_BUFFER_SIZE = 4 * 1024;

    /**
     * Max Outgoing Frames - Set the maximum number of data frames allowed to be waiting to be sent at any one time.
     */
    public static final int DEFAULT_MAX_OUTGOING_FRAMES = -1;

    /**
     * Auto Fragment - If set to true, frames are automatically fragmented to respect the maximum frame size.
     */
    public static final boolean DEFAULT_AUTO_FRAGMENT = true;

    /**
     * Idle Timeout - The duration that a websocket may be idle before being closed by the implementation.
     */
    public static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Write Timeout - The maximum time a frame may be waiting to be sent.
     */
    public static final Duration DEFAULT_WRITE_TIMEOUT = Duration.ZERO;

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
