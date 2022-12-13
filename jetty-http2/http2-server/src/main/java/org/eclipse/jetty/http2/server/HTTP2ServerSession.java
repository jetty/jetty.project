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

package org.eclipse.jetty.http2.server;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.CloseState;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.FlowControlStrategy;
import org.eclipse.jetty.http2.HTTP2Session;
import org.eclipse.jetty.http2.IStream;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PushPromiseFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.http2.frames.WindowUpdateFrame;
import org.eclipse.jetty.http2.generator.Generator;
import org.eclipse.jetty.http2.parser.ServerParser;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HTTP2ServerSession extends HTTP2Session implements ServerParser.Listener
{
    private static final Logger LOG = LoggerFactory.getLogger(HTTP2ServerSession.class);

    private final ServerSessionListener listener;

    public HTTP2ServerSession(Scheduler scheduler, EndPoint endPoint, Generator generator, ServerSessionListener listener, FlowControlStrategy flowControl)
    {
        super(scheduler, endPoint, generator, listener, flowControl, 2);
        this.listener = listener;
    }

    @Override
    public void onPreface()
    {
        // SPEC: send a SETTINGS frame upon receiving the preface.
        Map<Integer, Integer> settings = notifyPreface(this);
        if (settings == null)
            settings = Collections.emptyMap();
        SettingsFrame settingsFrame = new SettingsFrame(settings, false);

        int sessionWindow = getInitialSessionRecvWindow() - FlowControlStrategy.DEFAULT_WINDOW_SIZE;
        updateRecvWindow(sessionWindow);
        if (sessionWindow > 0)
            frames(null, List.of(settingsFrame, new WindowUpdateFrame(0, sessionWindow)), Callback.NOOP);
        else
            frames(null, List.of(settingsFrame), Callback.NOOP);
    }

    @Override
    public void onHeaders(HeadersFrame frame)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Received {}", frame);

        int streamId = frame.getStreamId();
        if (!isClientStream(streamId))
        {
            onConnectionFailure(ErrorCode.PROTOCOL_ERROR.code, "invalid_stream_id");
            return;
        }

        IStream stream = getStream(streamId);

        MetaData metaData = frame.getMetaData();
        if (metaData.isRequest())
        {
            if (stream == null)
            {
                if (isRemoteStreamClosed(streamId))
                {
                    onConnectionFailure(ErrorCode.STREAM_CLOSED_ERROR.code, "unexpected_headers_frame");
                }
                else
                {
                    stream = createRemoteStream(streamId, (MetaData.Request)metaData);
                    if (stream != null)
                    {
                        onStreamOpened(stream);

                        if (metaData instanceof MetaData.ConnectRequest)
                        {
                            if (!isConnectProtocolEnabled() && ((MetaData.ConnectRequest)metaData).getProtocol() != null)
                            {
                                stream.reset(new ResetFrame(streamId, ErrorCode.PROTOCOL_ERROR.code), Callback.NOOP);
                                return;
                            }
                        }

                        stream.process(frame, Callback.NOOP);
                        boolean closed = stream.updateClose(frame.isEndStream(), CloseState.Event.RECEIVED);
                        Stream.Listener listener = notifyNewStream(stream, frame);
                        stream.setListener(listener);
                        if (closed)
                            removeStream(stream);
                    }
                }
            }
            else
            {
                onConnectionFailure(ErrorCode.PROTOCOL_ERROR.code, "duplicate_stream");
            }
        }
        else if (metaData.isResponse())
        {
            onConnectionFailure(ErrorCode.PROTOCOL_ERROR.code, "invalid_request");
        }
        else
        {
            // Trailers.
            if (stream != null)
            {
                stream.process(frame, Callback.NOOP);
                boolean closed = stream.updateClose(frame.isEndStream(), CloseState.Event.RECEIVED);
                notifyHeaders(stream, frame);
                if (closed)
                    removeStream(stream);
            }
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Stream #{} not found", streamId);
                onConnectionFailure(ErrorCode.PROTOCOL_ERROR.code, "unexpected_headers_frame");
            }
        }
    }

    @Override
    public void onPushPromise(PushPromiseFrame frame)
    {
        onConnectionFailure(ErrorCode.PROTOCOL_ERROR.code, "push_promise");
    }

    private Map<Integer, Integer> notifyPreface(Session session)
    {
        try
        {
            return listener.onPreface(session);
        }
        catch (Throwable x)
        {
            LOG.info("Failure while notifying listener {}", listener, x);
            return null;
        }
    }

    @Override
    public void onFrame(Frame frame)
    {
        switch (frame.getType())
        {
            case PREFACE:
                onPreface();
                break;
            case SETTINGS:
                // SPEC: the required reply to this SETTINGS frame is the 101 response.
                onSettings((SettingsFrame)frame, false);
                break;
            case HEADERS:
                onHeaders((HeadersFrame)frame);
                break;
            default:
                super.onFrame(frame);
                break;
        }
    }
}
