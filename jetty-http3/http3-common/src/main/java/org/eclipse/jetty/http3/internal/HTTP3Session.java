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

import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.frames.SettingsFrame;
import org.eclipse.jetty.http3.internal.parser.ParserListener;
import org.eclipse.jetty.quic.common.ProtocolSession;
import org.eclipse.jetty.quic.common.QuicStreamEndPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HTTP3Session implements Session, ParserListener
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

    protected HTTP3Stream newStream(QuicStreamEndPoint endPoint, Stream.Listener listener)
    {
        return streams.computeIfAbsent(endPoint.getStreamId(), id -> new HTTP3Stream(endPoint, listener));
    }

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
            LOG.debug("received {} on {}", frame, this);
    }
}
