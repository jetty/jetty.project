//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.http2.server;

import java.util.Collections;
import java.util.Map;

import org.eclipse.jetty.http.MetaData;
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
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.http2.frames.WindowUpdateFrame;
import org.eclipse.jetty.http2.generator.Generator;
import org.eclipse.jetty.http2.parser.ServerParser;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Scheduler;

public class HTTP2ServerSession extends HTTP2Session implements ServerParser.Listener
{
    private static final Logger LOG = Log.getLogger(HTTP2ServerSession.class);

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

        WindowUpdateFrame windowFrame = null;
        int sessionWindow = getInitialSessionRecvWindow() - FlowControlStrategy.DEFAULT_WINDOW_SIZE;
        if (sessionWindow > 0)
        {
            updateRecvWindow(sessionWindow);
            windowFrame = new WindowUpdateFrame(0, sessionWindow);
        }

        if (windowFrame == null)
            frames(null, Callback.NOOP, settingsFrame, Frame.EMPTY_ARRAY);
        else
            frames(null, Callback.NOOP, settingsFrame, windowFrame);
    }

    @Override
    public void onHeaders(HeadersFrame frame)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Received {}", frame);

        MetaData metaData = frame.getMetaData();
        if (metaData.isRequest())
        {
            IStream stream = createRemoteStream(frame.getStreamId());
            if (stream != null)
            {
                onStreamOpened(stream);
                stream.process(frame, Callback.NOOP);
                Stream.Listener listener = notifyNewStream(stream, frame);
                stream.setListener(listener);
            }
        }
        else if (metaData.isResponse())
        {
            onConnectionFailure(ErrorCode.PROTOCOL_ERROR.code, "invalid_request");
        }
        else
        {
            // Trailers.
            int streamId = frame.getStreamId();
            IStream stream = getStream(streamId);
            if (stream != null)
            {
                stream.process(frame, Callback.NOOP);
                notifyHeaders(stream, frame);
            }
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Ignoring {}, stream #{} not found", frame, streamId);
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
            LOG.info("Failure while notifying listener " + listener, x);
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
