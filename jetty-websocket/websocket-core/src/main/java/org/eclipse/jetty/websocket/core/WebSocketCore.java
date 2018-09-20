//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.util.B64Code;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class WebSocketCore
{
    // Supported Spec Version
    public static final int SPEC_VERSION = 13;

    /**
     * Globally Unique Identifier for use in WebSocket handshake within {@code Sec-WebSocket-Accept} and <code>Sec-WebSocket-Key</code> http headers.
     * <p>
     * See <a href="https://tools.ietf.org/html/rfc6455#section-1.3">Opening Handshake (Section 1.3)</a>
     */
    @SuppressWarnings("SpellCheckingInspection")
    private final static byte[] MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11".getBytes(StandardCharsets.ISO_8859_1);

    /**
     * Concatenate the provided key with the Magic GUID and return the Base64 encoded form.
     *
     * @param key
     *            the key to hash
     * @return the {@code Sec-WebSocket-Accept} header response (per opening handshake spec)
     */
    public static String hashKey(String key)
    {
        try
        {
            MessageDigest md = MessageDigest.getInstance("SHA1");
            md.update(key.getBytes(StandardCharsets.UTF_8));
            md.update(MAGIC);
            return new String(B64Code.encode(md.digest()));
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    /**
     * Behavior for how the WebSocket should operate.
     * <p>
     * This dictated by the <a href="https://tools.ietf.org/html/rfc6455">RFC 6455</a> spec in various places, where certain behavior must be performed depending on
     * operation as a <a href="https://tools.ietf.org/html/rfc6455#section-4.1">CLIENT</a> vs a <a href="https://tools.ietf.org/html/rfc6455#section-4.2">SERVER</a>
     */
    public enum Behavior
    {
        CLIENT,
        SERVER
    }
}

