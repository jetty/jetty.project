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

package org.eclipse.jetty.http2.frames;

import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.http2.ErrorCode;

public class GoAwayFrame extends Frame
{
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
        ErrorCode errorCode = ErrorCode.from(error);
        return String.format("%s,%d/%s/%s",
                super.toString(),
                lastStreamId,
                errorCode != null ? errorCode.toString() : String.valueOf(error),
                tryConvertPayload());
    }
}
