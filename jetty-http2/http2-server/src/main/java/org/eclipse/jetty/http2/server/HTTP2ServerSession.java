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

package org.eclipse.jetty.http2.server;

import java.util.Collections;
import java.util.Map;

import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.ErrorCodes;
import org.eclipse.jetty.http2.FlowControl;
import org.eclipse.jetty.http2.HTTP2Session;
import org.eclipse.jetty.http2.IStream;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PushPromiseFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
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

    public HTTP2ServerSession(Scheduler scheduler, EndPoint endPoint, Generator generator, ServerSessionListener listener, FlowControl flowControl, int maxStreams)
    {
        super(scheduler, endPoint, generator, listener, flowControl, maxStreams, 2);
        this.listener = listener;
    }

    @Override
    public boolean onPreface()
    {
        // SPEC: send a SETTINGS frame upon receiving the preface.
        Map<Integer, Integer> settings = notifyPreface(this);
        if (settings == null)
            settings = Collections.emptyMap();
        SettingsFrame frame = new SettingsFrame(settings, false);
        // TODO: consider sending a WINDOW_UPDATE to enlarge the session send window of the client.
        control(null, disconnectOnFailure(), frame, Frame.EMPTY_ARRAY);
        return false;
    }

    @Override
    public boolean onHeaders(HeadersFrame frame)
    {
        MetaData metaData = frame.getMetaData();
        if (metaData.isRequest())
        {
            IStream stream = createRemoteStream(frame.getStreamId());
            if (stream != null)
            {
                stream.updateClose(frame.isEndStream(), false);
                stream.process(frame, Callback.Adapter.INSTANCE);
                Stream.Listener listener = notifyNewStream(stream, frame);
                stream.setListener(listener);
                // The listener may have sent a frame that closed the stream.
                if (stream.isClosed())
                    removeStream(stream, false);
            }
        }
        else
        {
            onConnectionFailure(ErrorCodes.INTERNAL_ERROR, "invalid_request");
        }
        return false;
    }

    @Override
    public boolean onPushPromise(PushPromiseFrame frame)
    {
        onConnectionFailure(ErrorCodes.PROTOCOL_ERROR, "push_promise");
        return false;
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
}
