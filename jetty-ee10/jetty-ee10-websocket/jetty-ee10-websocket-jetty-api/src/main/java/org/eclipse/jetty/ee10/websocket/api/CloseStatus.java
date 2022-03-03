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

package org.eclipse.jetty.ee10.websocket.api;

public class CloseStatus
{
    private static final int MAX_CONTROL_PAYLOAD = 125;
    public static final int MAX_REASON_PHRASE = MAX_CONTROL_PAYLOAD - 2;

    private final int code;
    private final String phrase;

    /**
     * Creates a reason for closing a web socket connection with the given code and reason phrase.
     *
     * @param closeCode the close code
     * @param reasonPhrase the reason phrase
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
