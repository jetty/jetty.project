//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

public class CloseStatus
{
    private static final int MAX_CONTROL_PAYLOAD = 125;
    private static final int MAX_REASON_PHRASE = MAX_CONTROL_PAYLOAD - 2;

    /**
     * Convenience method for trimming a long reason phrase at the maximum reason phrase length.
     * 
     * @param reason
     *            the proposed reason phrase
     * @return the reason phrase (trimmed if needed)
     */
    public static String trimMaxReasonLength(String reason)
    {
        if (reason.length() > MAX_REASON_PHRASE)
        {
            return reason.substring(0,MAX_REASON_PHRASE);
        }
        else
        {
            return reason;
        }
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
