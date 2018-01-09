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

import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.websocket.core.frames.ControlFrame;

/**
 * Representation of a WebSocket Close (status code &amp; reason)
 */
public class CloseStatus
{
    public static final int NORMAL = 1000;
    public static final int SHUTDOWN = 1001;
    public static final int PROTOCOL = 1002;
    public static final int BAD_DATA = 1003;
    public static final int NO_CODE = 1005;
    public static final int NO_CLOSE = 1006;
    public static final int BAD_PAYLOAD = 1007;
    public static final int POLICY_VIOLATION = 1008;
    public static final int MESSAGE_TOO_LARGE = 1009;
    public static final int EXTENSION_ERROR = 1010;
    public static final int SERVER_ERROR = 1011;
    public static final int FAILED_TLS_HANDSHAKE = 1015;
    
    public static final int MAX_REASON_PHRASE = ControlFrame.MAX_CONTROL_PAYLOAD - 2;

    /**
     * Convenience method for trimming a long reason reason at the maximum reason reason length of 123 UTF-8 bytes (per WebSocket spec).
     *
     * @param reason the proposed reason reason
     * @return the reason reason (trimmed if needed)
     * @deprecated use of this method is strongly discouraged, as it creates too many new objects that are just thrown away to accomplish its goals.
     */
    @Deprecated
    public static String trimMaxReasonLength(String reason)
    {
        if (reason == null)
        {
            return null;
        }

        byte[] reasonBytes = reason.getBytes(StandardCharsets.UTF_8);
        if (reasonBytes.length > MAX_REASON_PHRASE)
        {
            byte[] trimmed = new byte[MAX_REASON_PHRASE];
            System.arraycopy(reasonBytes, 0, trimmed, 0, MAX_REASON_PHRASE);
            return new String(trimmed, StandardCharsets.UTF_8);
        }

        return reason;
    }

    private int code;
    private String reason;

    /**
     * Creates a reason for closing a web socket connection with the no given status code.
     */
    public CloseStatus()
    {
        this(WebSocketConstants.NO_CODE);
    }

    /**
     * Creates a reason for closing a web socket connection with the given status code and no reason phrase.
     *
     * @param statusCode the close code
     */
    public CloseStatus(int statusCode)
    {
        this(statusCode, null);
    }

    /**
     * Creates a reason for closing a web socket connection with the given status code and reason phrase.
     *
     * @param statusCode the close code
     * @param reasonPhrase the reason phrase
     */
    public CloseStatus(int statusCode, String reasonPhrase)
    {
        this.code = statusCode;
        this.reason = reasonPhrase;
        if (reasonPhrase != null && reasonPhrase.length() > MAX_REASON_PHRASE)
        {
            throw new IllegalArgumentException("Phrase exceeds maximum length of " + MAX_REASON_PHRASE);
        }
    }

    public int getCode()
    {
        return code;
    }

    public String getReason()
    {
        return reason;
    }
    
    @Override
    public String toString()
    {
        return String.format("{%03d,%s}",code,reason);
    }
}
