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

import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.FrameType;
import org.eclipse.jetty.http2.frames.GoAwayFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PingFrame;
import org.eclipse.jetty.http2.frames.PriorityFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.http2.frames.WindowUpdateFrame;
import org.eclipse.jetty.http2.hpack.HpackDecoder;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class Parser
{
    private static final Logger LOG = Log.getLogger(Parser.class);

    private final HeaderParser headerParser = new HeaderParser();
    private final BodyParser[] bodyParsers = new BodyParser[FrameType.values().length];
    private final Listener listener;
    private State state = State.HEADER;

    public Parser(ByteBufferPool byteBufferPool, Listener listener)
    {
        this.listener = listener;

        HeaderBlockParser headerBlockParser = new HeaderBlockParser(byteBufferPool, new HpackDecoder());

        bodyParsers[FrameType.DATA.getType()] = new DataBodyParser(headerParser, listener);
        bodyParsers[FrameType.HEADERS.getType()] = new HeadersBodyParser(headerParser, listener, headerBlockParser);
        bodyParsers[FrameType.PRIORITY.getType()] = new PriorityBodyParser(headerParser, listener);
        bodyParsers[FrameType.RST_STREAM.getType()] = new ResetBodyParser(headerParser, listener);
        bodyParsers[FrameType.SETTINGS.getType()] = new SettingsBodyParser(headerParser, listener);
        bodyParsers[FrameType.PUSH_PROMISE.getType()] = null; // TODO
        bodyParsers[FrameType.PING.getType()] = new PingBodyParser(headerParser, listener);
        bodyParsers[FrameType.GO_AWAY.getType()] = new GoAwayBodyParser(headerParser, listener);
        bodyParsers[FrameType.WINDOW_UPDATE.getType()] = new WindowUpdateBodyParser(headerParser, listener);
        bodyParsers[FrameType.CONTINUATION.getType()] = null; // TODO
        bodyParsers[FrameType.ALTSVC.getType()] = null; // TODO
        bodyParsers[FrameType.BLOCKED.getType()] = null; // TODO
    }

    private void reset()
    {
        headerParser.reset();
        state = State.HEADER;
    }

    public boolean parse(ByteBuffer buffer)
    {
        LOG.debug("Parsing {}", buffer);

        while (true)
        {
            switch (state)
            {
                case HEADER:
                {
                    if (!headerParser.parse(buffer))
                        return false;
                    state = State.BODY;
                    break;
                }
                case BODY:
                {
                    int type = headerParser.getFrameType();
                    if (type < 0 || type >= bodyParsers.length)
                    {
                        notifyConnectionFailure(ErrorCode.PROTOCOL_ERROR, "unknown_frame_type_" + type);
                        return false;
                    }
                    BodyParser bodyParser = bodyParsers[type];
                    if (headerParser.getLength() == 0)
                    {
                        boolean async = bodyParser.emptyBody();
                        reset();
                        if (async)
                            return true;
                        if (!buffer.hasRemaining())
                            return false;
                    }
                    else
                    {
                        BodyParser.Result result = bodyParser.parse(buffer);
                        switch (result)
                        {
                            case PENDING:
                            {
                                // Not enough bytes.
                                return false;
                            }
                            case ASYNC:
                            {
                                // The content will be processed asynchronously, stop parsing;
                                // the asynchronous operation will eventually resume parsing.
                                if (LOG.isDebugEnabled())
                                    LOG.debug("Parsed {} frame, asynchronous processing", FrameType.from(type));
                                return true;
                            }
                            case COMPLETE:
                            {
                                if (LOG.isDebugEnabled())
                                    LOG.debug("Parsed {} frame, synchronous processing", FrameType.from(type));
                                reset();
                                break;
                            }
                            default:
                            {
                                throw new IllegalStateException();
                            }
                        }
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
        public boolean onData(DataFrame frame);

        public boolean onHeaders(HeadersFrame frame);

        public boolean onPriority(PriorityFrame frame);

        public boolean onReset(ResetFrame frame);

        public boolean onSettings(SettingsFrame frame);

        public boolean onPing(PingFrame frame);

        public boolean onGoAway(GoAwayFrame frame);

        public boolean onWindowUpdate(WindowUpdateFrame frame);

        public void onConnectionFailure(int error, String reason);

        public static class Adapter implements Listener
        {
            @Override
            public boolean onData(DataFrame frame)
            {
                return false;
            }

            @Override
            public boolean onHeaders(HeadersFrame frame)
            {
                return false;
            }

            @Override
            public boolean onPriority(PriorityFrame frame)
            {
                return false;
            }

            @Override
            public boolean onReset(ResetFrame frame)
            {
                return false;
            }

            @Override
            public boolean onSettings(SettingsFrame frame)
            {
                return false;
            }

            @Override
            public boolean onPing(PingFrame frame)
            {
                return false;
            }

            @Override
            public boolean onGoAway(GoAwayFrame frame)
            {
                return false;
            }

            @Override
            public boolean onWindowUpdate(WindowUpdateFrame frame)
            {
                return false;
            }

            @Override
            public void onConnectionFailure(int error, String reason)
            {
            }
        }
    }

    private enum State
    {
        HEADER, BODY
    }
}
