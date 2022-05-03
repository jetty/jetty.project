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

package org.eclipse.jetty.http2.client;

import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.CloseState;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.FlowControlStrategy;
import org.eclipse.jetty.http2.HTTP2Session;
import org.eclipse.jetty.http2.IStream;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PushPromiseFrame;
import org.eclipse.jetty.http2.generator.Generator;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HTTP2ClientSession extends HTTP2Session
{
    private static final Logger LOG = LoggerFactory.getLogger(HTTP2ClientSession.class);

    public HTTP2ClientSession(Scheduler scheduler, EndPoint endPoint, Generator generator, Session.Listener listener, FlowControlStrategy flowControl)
    {
        super(scheduler, endPoint, generator, listener, flowControl, 1);
    }

    @Override
    public void onHeaders(HeadersFrame frame)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Received {}", frame);

        // HEADERS can be received for normal and pushed responses.
        int streamId = frame.getStreamId();
        IStream stream = getStream(streamId);
        if (stream != null)
        {
            MetaData metaData = frame.getMetaData();
            if (metaData.isRequest())
            {
                onConnectionFailure(ErrorCode.PROTOCOL_ERROR.code, "invalid_response");
            }
            else
            {
                stream.process(frame, Callback.NOOP);
                if (stream.updateClose(frame.isEndStream(), CloseState.Event.RECEIVED))
                    removeStream(stream);
                notifyHeaders(stream, frame);
            }
        }
        else
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Stream #{} not found", streamId);
            if (isClientStream(streamId))
            {
                // Normal stream.
                // Headers or trailers arriving after
                // the stream has been reset are ignored.
                if (!isLocalStreamClosed(streamId))
                    onConnectionFailure(ErrorCode.PROTOCOL_ERROR.code, "unexpected_headers_frame");
            }
            else
            {
                // Pushed stream.
                // Headers or trailers arriving after
                // the stream has been reset are ignored.
                if (!isRemoteStreamClosed(streamId))
                    onConnectionFailure(ErrorCode.PROTOCOL_ERROR.code, "unexpected_headers_frame");
            }
        }
    }

    @Override
    public void onPushPromise(PushPromiseFrame frame)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Received {}", frame);

        int streamId = frame.getStreamId();
        int pushStreamId = frame.getPromisedStreamId();
        IStream stream = getStream(streamId);
        if (stream == null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Ignoring {}, stream #{} not found", frame, streamId);
        }
        else
        {
            IStream pushStream = createRemoteStream(pushStreamId, frame.getMetaData());
            if (pushStream != null)
            {
                pushStream.process(frame, Callback.NOOP);
                Stream.Listener listener = notifyPush(stream, pushStream, frame);
                pushStream.setListener(listener);
            }
        }
    }

    private Stream.Listener notifyPush(IStream stream, IStream pushStream, PushPromiseFrame frame)
    {
        Stream.Listener listener = stream.getListener();
        if (listener == null)
            return null;
        try
        {
            return listener.onPush(pushStream, frame);
        }
        catch (Throwable x)
        {
            LOG.info("Failure while notifying listener {}", listener, x);
            return null;
        }
    }
}
