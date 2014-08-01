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

package org.eclipse.jetty.http2.parser;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class PrefaceParser
{
    public static final byte[] PREFACE_BYTES = new byte[]
            {
                    0x50, 0x52, 0x49, 0x20, 0x2a, 0x20, 0x48, 0x54,
                    0x54, 0x50, 0x2f, 0x32, 0x2e, 0x30, 0x0d, 0x0a,
                    0x0d, 0x0a, 0x53, 0x4d, 0x0d, 0x0a, 0x0d, 0x0a
            };
    private static final Logger LOG = Log.getLogger(PrefaceParser.class);

    private final Parser.Listener listener;
    private int cursor;

    public PrefaceParser(Parser.Listener listener)
    {
        this.listener = listener;
    }

    public boolean parse(ByteBuffer buffer)
    {
        while (buffer.hasRemaining())
        {
            int currByte = buffer.get();
            if (currByte != PREFACE_BYTES[cursor])
            {
                notifyConnectionFailure(ErrorCode.PROTOCOL_ERROR, "invalid_preface");
                return false;
            }
            ++cursor;
            if (cursor == PREFACE_BYTES.length)
            {
                cursor = 0;
                if (LOG.isDebugEnabled())
                    LOG.debug("Parsed preface bytes");
                return true;
            }
        }
        return false;
    }

    protected void notifyConnectionFailure(int error, String reason)
    {
        try
        {
            listener.onConnectionFailure(error, reason);
        }
        catch (Throwable x)
        {
            LOG.info("Failure while notifying listener " + listener, x);
        }
    }
}
