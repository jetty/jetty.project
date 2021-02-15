//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.http2.frames;

import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.http2.ErrorCode;

public class GoAwayFrame extends Frame
{
    public static final GoAwayFrame GRACEFUL = new GoAwayFrame(Integer.MAX_VALUE, ErrorCode.NO_ERROR.code, new byte[]{'g', 'r', 'a', 'c', 'e', 'f', 'u', 'l'});

    private final int lastStreamId;
    private final int error;
    private final byte[] payload;

    public GoAwayFrame(int lastStreamId, int error, byte[] payload)
    {
        super(FrameType.GO_AWAY);
        this.lastStreamId = lastStreamId;
        this.error = error;
        this.payload = payload;
    }

    /**
     * @return whether this GOAWAY frame is graceful, i.e. its {@code lastStreamId == Integer.MAX_VALUE}
     */
    public boolean isGraceful()
    {
        // SPEC: section 6.8.
        return lastStreamId == Integer.MAX_VALUE;
    }

    public int getLastStreamId()
    {
        return lastStreamId;
    }

    public int getError()
    {
        return error;
    }

    public byte[] getPayload()
    {
        return payload;
    }

    public String tryConvertPayload()
    {
        if (payload == null || payload.length == 0)
            return "";
        try
        {
            return new String(payload, StandardCharsets.UTF_8);
        }
        catch (Throwable x)
        {
            return "";
        }
    }

    @Override
    public String toString()
    {
        return String.format("%s{%d/%s/%s}",
            super.toString(),
            lastStreamId,
            ErrorCode.toString(error, null),
            tryConvertPayload());
    }
}
