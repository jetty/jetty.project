//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http2.parser;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.frames.PrefaceFrame;
import org.eclipse.jetty.util.BufferUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PrefaceParser
{
    private static final Logger LOG = LoggerFactory.getLogger(PrefaceParser.class);

    private final Parser.Listener listener;
    private int cursor;

    public PrefaceParser(Parser.Listener listener)
    {
        this.listener = listener;
    }

    /**
     * <p>Advances this parser after the {@link PrefaceFrame#PREFACE_PREAMBLE_BYTES}.</p>
     * <p>This allows the HTTP/1.1 parser to parse the preamble of the preface,
     * which is a legal HTTP/1.1 request, and this parser will parse the remaining
     * bytes, that are not parseable by an HTTP/1.1 parser.</p>
     */
    protected void directUpgrade()
    {
        if (cursor != 0)
            throw new IllegalStateException();
        cursor = PrefaceFrame.PREFACE_PREAMBLE_BYTES.length;
    }

    public boolean parse(ByteBuffer buffer)
    {
        while (buffer.hasRemaining())
        {
            int currByte = buffer.get();
            if (currByte != PrefaceFrame.PREFACE_BYTES[cursor])
            {
                BufferUtil.clear(buffer);
                notifyConnectionFailure(ErrorCode.PROTOCOL_ERROR.code, "invalid_preface");
                return false;
            }
            ++cursor;
            if (cursor == PrefaceFrame.PREFACE_BYTES.length)
            {
                cursor = 0;
                if (LOG.isDebugEnabled())
                    LOG.debug("Parsed preface bytes from {}", buffer);
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
            LOG.info("Failure while notifying listener {}", listener, x);
        }
    }
}
