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

package org.eclipse.jetty.http3.internal.parser;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.eclipse.jetty.http3.frames.GoAwayFrame;
import org.eclipse.jetty.http3.frames.SettingsFrame;
import org.eclipse.jetty.http3.internal.HTTP3ErrorCode;
import org.eclipse.jetty.util.BufferUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>The base parser for the frame body of HTTP/3 frames.</p>
 * <p>Subclasses implement {@link #parse(ByteBuffer)} to parse
 * the frame specific body.</p>
 *
 * @see MessageParser
 */
public abstract class BodyParser
{
    private static final Logger LOG = LoggerFactory.getLogger(BodyParser.class);

    private final HeaderParser headerParser;
    private final ParserListener listener;

    protected BodyParser(HeaderParser headerParser, ParserListener listener)
    {
        this.headerParser = headerParser;
        this.listener = listener;
    }

    protected ParserListener getParserListener()
    {
        return listener;
    }

    protected long getBodyLength()
    {
        return headerParser.getFrameLength();
    }

    /**
     * <p>Parses the frame body bytes in the given {@code buffer}.</p>
     * <p>Only the frame body bytes are consumed, therefore when this method returns,
     * the buffer may contain unconsumed bytes, for example for other frames.</p>
     *
     * @param buffer the buffer to parse
     * @return true if all the frame body bytes were parsed;
     * false if not enough frame body bytes were present in the buffer
     */
    public abstract Result parse(ByteBuffer buffer);

    protected void emptyBody(ByteBuffer buffer)
    {
        sessionFailure(buffer, HTTP3ErrorCode.PROTOCOL_ERROR.code(), "invalid_frame", new IOException("invalid empty body frame"));
    }

    protected void sessionFailure(ByteBuffer buffer, long error, String reason, Throwable failure)
    {
        BufferUtil.clear(buffer);
        notifySessionFailure(error, reason, failure);
    }

    protected void notifySessionFailure(long error, String reason, Throwable failure)
    {
        try
        {
            listener.onSessionFailure(error, reason, failure);
        }
        catch (Throwable x)
        {
            LOG.info("failure while notifying listener {}", listener, x);
        }
    }

    protected void notifyStreamFailure(long streamId, long error, Throwable failure)
    {
        try
        {
            listener.onStreamFailure(streamId, error, failure);
        }
        catch (Throwable x)
        {
            LOG.info("failure while notifying listener {}", listener, x);
        }
    }

    protected void notifySettings(SettingsFrame frame)
    {
        try
        {
            listener.onSettings(frame);
        }
        catch (Throwable x)
        {
            LOG.info("failure while notifying listener {}", listener, x);
        }
    }

    protected void notifyGoAway(GoAwayFrame frame)
    {
        try
        {
            listener.onGoAway(frame);
        }
        catch (Throwable x)
        {
            LOG.info("failure while notifying listener {}", listener, x);
        }
    }

    public enum Result
    {
        NO_FRAME, FRAGMENT_FRAME, WHOLE_FRAME
    }
}
