//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http3.internal;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.frames.DataFrame;
import org.eclipse.jetty.http3.frames.Frame;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.frames.SettingsFrame;
import org.eclipse.jetty.http3.internal.parser.ParserListener;
import org.eclipse.jetty.quic.common.ProtocolSession;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class HTTP3Session implements Session, ParserListener
{
    private static final Logger LOG = LoggerFactory.getLogger(HTTP3Session.class);

    private final Map<Long, HTTP3Stream> streams = new ConcurrentHashMap<>();
    private final ProtocolSession session;
    private final Listener listener;

    public HTTP3Session(ProtocolSession session, Listener listener)
    {
        this.session = session;
        this.listener = listener;
    }

    public ProtocolSession getProtocolSession()
    {
        return session;
    }

    protected HTTP3Stream createStream(long streamId)
    {
        HTTP3Stream stream = new HTTP3Stream(this, streamId);
        if (streams.put(streamId, stream) != null)
            throw new IllegalStateException("duplicate stream id " + streamId);
        return stream;
    }

    protected HTTP3Stream getOrCreateStream(long streamId)
    {
        return streams.computeIfAbsent(streamId, id -> new HTTP3Stream(this, streamId));
    }

    protected HTTP3Stream getStream(long streamId)
    {
        return streams.get(streamId);
    }

    protected abstract void writeFrame(long streamId, Frame frame, Callback callback);

    public Map<Long, Long> onPreface()
    {
        Map<Long, Long> settings = notifyPreface();
        if (LOG.isDebugEnabled())
            LOG.debug("produced settings {} on {}", settings, this);
        return settings;
    }

    private Map<Long, Long> notifyPreface()
    {
        try
        {
            return listener.onPreface(this);
        }
        catch (Throwable x)
        {
            LOG.info("failure notifying listener {}", listener, x);
            return null;
        }
    }

    @Override
    public void onSettings(SettingsFrame frame)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("received {} on {}", frame, this);
        notifySettings(frame);
    }

    private void notifySettings(SettingsFrame frame)
    {
        try
        {
            listener.onSettings(this, frame);
        }
        catch (Throwable x)
        {
            LOG.info("failure notifying listener {}", listener, x);
        }
    }

    @Override
    public void onHeaders(long streamId, HeadersFrame frame)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("received {}#{} on {}", frame, streamId, this);

        HTTP3Stream stream = getOrCreateStream(streamId);

        MetaData metaData = frame.getMetaData();
        if (metaData.isRequest())
        {
            Stream.Listener streamListener = notifyRequest(stream, frame);
            stream.setListener(streamListener);
        }
        else if (metaData.isResponse())
        {
            notifyResponse(stream, frame);
        }
        else
        {
            notifyTrailer(stream, frame);
        }
    }

    private Stream.Listener notifyRequest(HTTP3Stream stream, HeadersFrame frame)
    {
        try
        {
            return listener.onRequest(stream, frame);
        }
        catch (Throwable x)
        {
            LOG.info("failure notifying listener {}", listener, x);
            return null;
        }
    }

    private void notifyResponse(HTTP3Stream stream, HeadersFrame frame)
    {
        try
        {
            stream.getListener().onResponse(stream, frame);
        }
        catch (Throwable x)
        {
            LOG.info("failure notifying listener {}", listener, x);
        }
    }

    private void notifyTrailer(HTTP3Stream stream, HeadersFrame frame)
    {
        try
        {
            stream.getListener().onTrailer(stream, frame);
        }
        catch (Throwable x)
        {
            LOG.info("failure notifying listener {}", listener, x);
        }
    }

    @Override
    public void onData(long streamId, DataFrame frame)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("received {}#{} on {}", frame, streamId, this);

        // The stream must already exist.
        HTTP3Stream stream = getStream(streamId);
        // TODO: handle null stream.

        // TODO: implement demand mechanism like in HTTP2Stream
        //  demand(n) should be on Stream, or on a LongConsumer parameter?

        // TODO: the callback in HTTP2 was only to notify of data consumption for flow control.
        //  Here, we don't have to do flow control, but what about retain()/release() for the network buffer?
        notifyData(stream, frame, Callback.NOOP);
    }

    private void notifyData(HTTP3Stream stream, DataFrame frame, Callback callback)
    {
        stream.getListener().onData(stream, frame, callback);
    }
}
