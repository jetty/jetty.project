//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http3.server.internal;

import org.eclipse.jetty.http3.HTTP3Session;
import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.frames.Frame;
import org.eclipse.jetty.http3.frames.GoAwayFrame;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.frames.SettingsFrame;
import org.eclipse.jetty.quic.common.StreamEndPoint;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HTTP3SessionServer extends HTTP3Session implements Session.Server
{
    private static final Logger LOG = LoggerFactory.getLogger(HTTP3SessionServer.class);

    public HTTP3SessionServer(Scheduler scheduler, ServerHTTP3Session session, Session.Server.Listener listener)
    {
        super(scheduler, session, listener);
    }

    @Override
    public ServerHTTP3Session getProtocolSession()
    {
        return (ServerHTTP3Session)super.getProtocolSession();
    }

    @Override
    public Session.Server.Listener getListener()
    {
        return (Session.Server.Listener)super.getListener();
    }

    @Override
    public void onOpen()
    {
        super.onOpen();
        notifyAccept();
    }

    @Override
    protected HTTP3StreamServer newHTTP3Stream(StreamEndPoint endPoint, boolean local)
    {
        return new HTTP3StreamServer(this, endPoint, local);
    }

    @Override
    public void onHeaders(long streamId, HeadersFrame frame, boolean wasBlocked)
    {
        if (frame.getMetaData().isRequest())
        {
            StreamEndPoint endPoint = getProtocolSession().getStreamEndPoint(streamId);
            HTTP3StreamServer stream = (HTTP3StreamServer)createStream(endPoint);
            if (LOG.isDebugEnabled())
                LOG.debug("received request {} on {}", frame, stream);
            stream.onRequest(frame);
        }
        else
        {
            super.onHeaders(streamId, frame, wasBlocked);
        }
    }

    @Override
    public void onSettings(SettingsFrame frame)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("received {} on {}", frame, this);
        getProtocolSession().onSettings(frame);
        super.onSettings(frame);
    }

    @Override
    public void writeControlFrame(Frame frame, Callback callback)
    {
        getProtocolSession().writeControlFrame(frame, callback);
    }

    @Override
    public void writeMessageFrame(StreamEndPoint streamEndPoint, Frame frame, Callback callback)
    {
        getProtocolSession().writeMessageFrame(streamEndPoint, frame, callback);
    }

    @Override
    protected GoAwayFrame newGoAwayFrame(boolean graceful)
    {
        if (graceful)
            return GoAwayFrame.SERVER_GRACEFUL;
        return super.newGoAwayFrame(graceful);
    }

    private void notifyAccept()
    {
        Server.Listener listener = getListener();
        try
        {
            listener.onAccept(this);
        }
        catch (Throwable x)
        {
            LOG.info("failure notifying listener {}", listener, x);
        }
    }
}
