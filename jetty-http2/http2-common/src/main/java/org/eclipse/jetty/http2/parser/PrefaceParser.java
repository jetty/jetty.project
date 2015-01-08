//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.http2.ErrorCodes;
import org.eclipse.jetty.http2.frames.PrefaceFrame;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class PrefaceParser
{
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
            if (currByte != PrefaceFrame.PREFACE_BYTES[cursor])
            {
                notifyConnectionFailure(ErrorCodes.PROTOCOL_ERROR, "invalid_preface");
                return false;
            }
            ++cursor;
            if (cursor == PrefaceFrame.PREFACE_BYTES.length)
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
