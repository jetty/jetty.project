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

package org.eclipse.jetty.http2.client;

import org.eclipse.jetty.http2.FlowControlStrategy;
import org.eclipse.jetty.http2.HTTP2Session;
import org.eclipse.jetty.http2.IStream;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PushPromiseFrame;
import org.eclipse.jetty.http2.generator.Generator;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Scheduler;

public class HTTP2ClientSession extends HTTP2Session
{
    private static final Logger LOG = Log.getLogger(HTTP2ClientSession.class);

    public HTTP2ClientSession(Scheduler scheduler, EndPoint endPoint, Generator generator, Listener listener, FlowControlStrategy flowControl)
    {
        super(scheduler, endPoint, generator, listener, flowControl, 1);
    }

    @Override
    public void onHeaders(HeadersFrame frame)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Received {}", frame);

        int streamId = frame.getStreamId();
        IStream stream = getStream(streamId);
        if (stream == null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Ignoring {}, stream #{} not found", frame, streamId);
        }
        else
        {
            stream.process(frame, Callback.Adapter.INSTANCE);
            notifyHeaders(stream, frame);
            if (stream.isClosed())
                removeStream(stream, false);
        }
    }

    private void notifyHeaders(IStream stream, HeadersFrame frame)
    {
        Stream.Listener listener = stream.getListener();
        if (listener == null)
            return;
        try
        {
            listener.onHeaders(stream, frame);
        }
        catch (Throwable x)
        {
            LOG.info("Failure while notifying listener " + listener, x);
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
            IStream pushStream = createRemoteStream(pushStreamId);
            pushStream.process(frame, Callback.Adapter.INSTANCE);
            Stream.Listener listener = notifyPush(stream, pushStream, frame);
            pushStream.setListener(listener);
            if (pushStream.isClosed())
                removeStream(pushStream, false);
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
            LOG.info("Failure while notifying listener " + listener, x);
            return null;
        }
    }
}
