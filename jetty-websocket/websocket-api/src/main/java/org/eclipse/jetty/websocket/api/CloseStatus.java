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

package org.eclipse.jetty.websocket.api;

import java.nio.charset.StandardCharsets;

public class CloseStatus
{
    private static final int MAX_CONTROL_PAYLOAD = 125;
    public static final int MAX_REASON_PHRASE = MAX_CONTROL_PAYLOAD - 2;

    /**
     * Convenience method for trimming a long reason phrase at the maximum reason phrase length of 123 UTF-8 bytes (per WebSocket spec).
     * 
     * @param reason
     *            the proposed reason phrase
     * @return the reason phrase (trimmed if needed)
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
            System.arraycopy(reasonBytes,0,trimmed,0,MAX_REASON_PHRASE);
            return new String(trimmed,StandardCharsets.UTF_8);
        }
        
        return reason;
    }

    private int code;
    private String phrase;

    /**
     * Creates a reason for closing a web socket connection with the given code and reason phrase.
     * 
     * @param closeCode
     *            the close code
     * @param reasonPhrase
     *            the reason phrase
     * @see StatusCode
     */
    public CloseStatus(int closeCode, String reasonPhrase)
    {
        this.code = closeCode;
        this.phrase = reasonPhrase;
        if (reasonPhrase.length() > MAX_REASON_PHRASE)
        {
            throw new IllegalArgumentException("Phrase exceeds maximum length of " + MAX_REASON_PHRASE);
        }
    }

    public int getCode()
    {
        return code;
    }

    public String getPhrase()
    {
        return phrase;
    }
}
