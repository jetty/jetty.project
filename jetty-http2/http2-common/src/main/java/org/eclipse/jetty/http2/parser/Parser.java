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

import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.FrameType;
import org.eclipse.jetty.http2.frames.GoAwayFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PingFrame;
import org.eclipse.jetty.http2.frames.PriorityFrame;
import org.eclipse.jetty.http2.frames.PushPromiseFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.http2.frames.WindowUpdateFrame;
import org.eclipse.jetty.http2.hpack.HpackDecoder;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * <p>The HTTP/2 protocol parser.</p>
 * <p>This parser makes use of the {@link HeaderParser} and of
 * {@link BodyParser}s to parse HTTP/2 frames.</p>
 */
public class Parser
{
    private static final Logger LOG = Log.getLogger(Parser.class);

    private final Listener listener;
    private final HeaderParser headerParser;
    private final BodyParser[] bodyParsers;
    private State state = State.HEADER;

    public Parser(ByteBufferPool byteBufferPool, Listener listener, int maxDynamicTableSize, int maxHeaderSize)
    {
        this.listener = listener;
        this.headerParser = new HeaderParser();
        this.bodyParsers = new BodyParser[FrameType.values().length];

        HeaderBlockParser headerBlockParser = new HeaderBlockParser(byteBufferPool, new HpackDecoder(maxDynamicTableSize, maxHeaderSize));

        bodyParsers[FrameType.DATA.getType()] = new DataBodyParser(headerParser, listener);
        bodyParsers[FrameType.HEADERS.getType()] = new HeadersBodyParser(headerParser, listener, headerBlockParser);
        bodyParsers[FrameType.PRIORITY.getType()] = new PriorityBodyParser(headerParser, listener);
        bodyParsers[FrameType.RST_STREAM.getType()] = new ResetBodyParser(headerParser, listener);
        bodyParsers[FrameType.SETTINGS.getType()] = new SettingsBodyParser(headerParser, listener);
        bodyParsers[FrameType.PUSH_PROMISE.getType()] = new PushPromiseBodyParser(headerParser, listener, headerBlockParser);
        bodyParsers[FrameType.PING.getType()] = new PingBodyParser(headerParser, listener);
        bodyParsers[FrameType.GO_AWAY.getType()] = new GoAwayBodyParser(headerParser, listener);
        bodyParsers[FrameType.WINDOW_UPDATE.getType()] = new WindowUpdateBodyParser(headerParser, listener);
        bodyParsers[FrameType.CONTINUATION.getType()] = new ContinuationBodyParser(headerParser, listener, headerBlockParser);
    }

    private void reset()
    {
        headerParser.reset();
        state = State.HEADER;
    }

    /**
     * <p>Parses the given {@code buffer} bytes and emit events to a {@link Listener}.</p>
     * <p>When this method returns, the buffer may not be fully consumed, so invocations
     * to this method should be wrapped in a loop:</p>
     * <pre>
     * while (buffer.hasRemaining())
     *     parser.parse(buffer);
     * </pre>
     *
     * @param buffer the buffer to parse
     */
    public void parse(ByteBuffer buffer)
    {
        try
        {
            while (true)
            {
                switch (state)
                {
                    case HEADER:
                    {
                        if (!headerParser.parse(buffer))
                            return;
                        state = State.BODY;
                        break;
                    }
                    case BODY:
                    {
                        int type = headerParser.getFrameType();
                        if (LOG.isDebugEnabled())
                            LOG.debug("Parsing {} frame", FrameType.from(type));
                        if (type < 0 || type >= bodyParsers.length)
                        {
                            BufferUtil.clear(buffer);
                            notifyConnectionFailure(ErrorCode.PROTOCOL_ERROR.code, "unknown_frame_type_" + type);
                            return;
                        }
                        BodyParser bodyParser = bodyParsers[type];
                        if (headerParser.getLength() == 0)
                        {
                            bodyParser.emptyBody(buffer);
                            reset();
                            if (!buffer.hasRemaining())
                                return;
                        }
                        else
                        {
                            if (!bodyParser.parse(buffer))
                                return;
                            if (LOG.isDebugEnabled())
                                LOG.debug("Parsed {} frame", FrameType.from(type));
                            reset();
                        }
                        break;
                    }
                    default:
                    {
                        throw new IllegalStateException();
                    }
                }
            }
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug(x);
            BufferUtil.clear(buffer);
            notifyConnectionFailure(ErrorCode.PROTOCOL_ERROR.code, "parser_error");
        }
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

    public interface Listener
    {
        public void onData(DataFrame frame);

        public void onHeaders(HeadersFrame frame);

        public void onPriority(PriorityFrame frame);

        public void onReset(ResetFrame frame);

        public void onSettings(SettingsFrame frame);

        public void onPushPromise(PushPromiseFrame frame);

        public void onPing(PingFrame frame);

        public void onGoAway(GoAwayFrame frame);

        public void onWindowUpdate(WindowUpdateFrame frame);

        public void onConnectionFailure(int error, String reason);

        public static class Adapter implements Listener
        {
            @Override
            public void onData(DataFrame frame)
            {
            }

            @Override
            public void onHeaders(HeadersFrame frame)
            {
            }

            @Override
            public void onPriority(PriorityFrame frame)
            {
            }

            @Override
            public void onReset(ResetFrame frame)
            {
            }

            @Override
            public void onSettings(SettingsFrame frame)
            {
            }

            @Override
            public void onPushPromise(PushPromiseFrame frame)
            {
            }

            @Override
            public void onPing(PingFrame frame)
            {
            }

            @Override
            public void onGoAway(GoAwayFrame frame)
            {
            }

            @Override
            public void onWindowUpdate(WindowUpdateFrame frame)
            {
            }

            @Override
            public void onConnectionFailure(int error, String reason)
            {
                LOG.warn("Connection failure: {}/{}", error, reason);
            }
        }
    }

    private enum State
    {
        HEADER, BODY
    }
}
