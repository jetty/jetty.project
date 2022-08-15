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

package org.eclipse.jetty.http2.internal.parser;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.GoAwayFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PingFrame;
import org.eclipse.jetty.http2.frames.PriorityFrame;
import org.eclipse.jetty.http2.frames.PushPromiseFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.http2.frames.WindowUpdateFrame;
import org.eclipse.jetty.http2.internal.ErrorCode;
import org.eclipse.jetty.http2.internal.Flags;
import org.eclipse.jetty.util.BufferUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>The base parser for the frame body of HTTP/2 frames.</p>
 * <p>Subclasses implement {@link #parse(ByteBuffer)} to parse
 * the frame specific body.</p>
 *
 * @see Parser
 */
public abstract class BodyParser
{
    protected static final Logger LOG = LoggerFactory.getLogger(BodyParser.class);

    private final HeaderParser headerParser;
    private final Parser.Listener listener;

    protected BodyParser(HeaderParser headerParser, Parser.Listener listener)
    {
        this.headerParser = headerParser;
        this.listener = listener;
    }

    /**
     * <p>Parses the body bytes in the given {@code buffer}; only the body
     * bytes are consumed, therefore when this method returns, the buffer
     * may contain unconsumed bytes.</p>
     *
     * @param buffer the buffer to parse
     * @return true if the whole body bytes were parsed, false if not enough
     * body bytes were present in the buffer
     */
    public abstract boolean parse(ByteBuffer buffer);

    protected void emptyBody(ByteBuffer buffer)
    {
        connectionFailure(buffer, ErrorCode.PROTOCOL_ERROR.code, "invalid_frame");
    }

    protected boolean hasFlag(int bit)
    {
        return headerParser.hasFlag(bit);
    }

    protected boolean isPadding()
    {
        return headerParser.hasFlag(Flags.PADDING);
    }

    protected boolean isEndStream()
    {
        return headerParser.hasFlag(Flags.END_STREAM);
    }

    protected int getStreamId()
    {
        return headerParser.getStreamId();
    }

    protected int getBodyLength()
    {
        return headerParser.getLength();
    }

    protected int getFrameType()
    {
        return headerParser.getFrameType();
    }

    protected void notifyData(DataFrame frame)
    {
        try
        {
            listener.onData(frame);
        }
        catch (Throwable x)
        {
            LOG.info("Failure while notifying listener {}", listener, x);
        }
    }

    protected void notifyHeaders(HeadersFrame frame)
    {
        try
        {
            listener.onHeaders(frame);
        }
        catch (Throwable x)
        {
            LOG.info("Failure while notifying listener {}", listener, x);
        }
    }

    protected void notifyPriority(PriorityFrame frame)
    {
        try
        {
            listener.onPriority(frame);
        }
        catch (Throwable x)
        {
            LOG.info("Failure while notifying listener {}", listener, x);
        }
    }

    protected void notifyReset(ResetFrame frame)
    {
        try
        {
            listener.onReset(frame);
        }
        catch (Throwable x)
        {
            LOG.info("Failure while notifying listener {}", listener, x);
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
            LOG.info("Failure while notifying listener {}", listener, x);
        }
    }

    protected void notifyPushPromise(PushPromiseFrame frame)
    {
        try
        {
            listener.onPushPromise(frame);
        }
        catch (Throwable x)
        {
            LOG.info("Failure while notifying listener {}", listener, x);
        }
    }

    protected void notifyPing(PingFrame frame)
    {
        try
        {
            listener.onPing(frame);
        }
        catch (Throwable x)
        {
            LOG.info("Failure while notifying listener {}", listener, x);
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
            LOG.info("Failure while notifying listener {}", listener, x);
        }
    }

    protected void notifyWindowUpdate(WindowUpdateFrame frame)
    {
        try
        {
            listener.onWindowUpdate(frame);
        }
        catch (Throwable x)
        {
            LOG.info("Failure while notifying listener {}", listener, x);
        }
    }

    protected boolean connectionFailure(ByteBuffer buffer, int error, String reason)
    {
        BufferUtil.clear(buffer);
        notifyConnectionFailure(error, reason);
        return false;
    }

    private void notifyConnectionFailure(int error, String reason)
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

    protected boolean streamFailure(int streamId, int error, String reason)
    {
        notifyStreamFailure(streamId, error, reason);
        return false;
    }

    private void notifyStreamFailure(int streamId, int error, String reason)
    {
        try
        {
            listener.onStreamFailure(streamId, error, reason);
        }
        catch (Throwable x)
        {
            LOG.info("Failure while notifying listener {}", listener, x);
        }
    }

    protected boolean rateControlOnEvent(Object o)
    {
        return headerParser.getRateControl().onEvent(o);
    }
}
