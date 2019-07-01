//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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
    public static final boolean DEFAULT_AUTO_FRAGMENT = true;
    public static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofSeconds(30);
    public static final Duration DEFAULT_WRITE_TIMEOUT = Duration.ZERO;

    /**
     * Globally Unique Identifier for use in WebSocket handshake within {@code Sec-WebSocket-Accept} and <code>Sec-WebSocket-Key</code> http headers.
     * <p>
     * See <a href="https://tools.ietf.org/html/rfc6455#section-1.3">Opening Handshake (Section 1.3)</a>
     */
    @SuppressWarnings("SpellCheckingInspection")
    public static final byte[] MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11".getBytes(StandardCharsets.ISO_8859_1);

    private WebSocketConstants()
    {
    }
}
