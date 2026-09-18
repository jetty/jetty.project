//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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
    /**
     * {@code PREFACE_AS_LONGS[i]} holds the 8 preface octets starting at index {@code i},
     * so that the preface can be compared 8 octets at a time from any offset.
     */
    private static final long[] PREFACE_AS_LONGS = prefaceAsLongs();

    private static long[] prefaceAsLongs()
    {
        ByteBuffer preface = ByteBuffer.wrap(PrefaceFrame.PREFACE_BYTES);
        long[] longs = new long[PrefaceFrame.PREFACE_BYTES.length - Long.BYTES + 1];
        for (int i = 0; i < longs.length; i++)
        {
            longs[i] = preface.getLong(i);
        }
        return longs;
    }

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
        int end = PrefaceFrame.PREFACE_BYTES.length;
        int needed = end - cursor;
        // Fast path: all the remaining preface octets are available in the
        // buffer, so compare them 8 at a time rather than one at a time.
        if (buffer.remaining() >= needed)
        {
            int position = buffer.position();
            int index = 0;
            for (; needed - index >= Long.BYTES; index += Long.BYTES)
            {
                if (buffer.getLong(position + index) != PREFACE_AS_LONGS[cursor + index])
                    return invalidPreface(buffer);
            }
            if (index < needed)
            {
                if (needed >= Long.BYTES)
                {
                    // Compare the last 8 octets, overlapping the ones already compared.
                    if (buffer.getLong(position + needed - Long.BYTES) != PREFACE_AS_LONGS[end - Long.BYTES])
                        return invalidPreface(buffer);
                }
                else
                {
                    // Fewer than 8 octets in total, for example after a direct upgrade.
                    for (; index < needed; index++)
                    {
                        if (buffer.get(position + index) != PrefaceFrame.PREFACE_BYTES[cursor + index])
                            return invalidPreface(buffer);
                    }
                }
            }
            buffer.position(position + needed);
            cursor = 0;
            if (LOG.isDebugEnabled())
                LOG.debug("Parsed preface bytes from {}", buffer);
            return true;
        }

        while (buffer.hasRemaining())
        {
            int currByte = buffer.get();
            if (currByte != PrefaceFrame.PREFACE_BYTES[cursor])
                return invalidPreface(buffer);
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

    private boolean invalidPreface(ByteBuffer buffer)
    {
        BufferUtil.clear(buffer);
        notifyConnectionFailure(ErrorCode.PROTOCOL_ERROR.code, "invalid_preface");
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
