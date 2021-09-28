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

package org.eclipse.jetty.http3.server.internal;

import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.frames.Frame;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.internal.HTTP3Session;
import org.eclipse.jetty.http3.internal.HTTP3Stream;
import org.eclipse.jetty.quic.common.QuicStreamEndPoint;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HTTP3SessionServer extends HTTP3Session implements Session.Server
{
    private static final Logger LOG = LoggerFactory.getLogger(HTTP3SessionServer.class);

    public HTTP3SessionServer(ServerHTTP3Session session, Session.Server.Listener listener)
    {
        super(session, listener);
    }

    @Override
    public void onOpen()
    {
        super.onOpen();
        notifyAccept();
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
    public void onHeaders(long streamId, HeadersFrame frame)
    {
        QuicStreamEndPoint endPoint = getProtocolSession().getStreamEndPoint(streamId);
        HTTP3Stream stream = getOrCreateStream(endPoint);
        MetaData metaData = frame.getMetaData();
        if (metaData.isRequest())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("received request {}#{} on {}", frame, streamId, this);
            stream.processRequest(frame);
        }
        else
        {
            super.onHeaders(streamId, frame);
        }
    }

    @Override
    public void writeFrame(long streamId, Frame frame, Callback callback)
    {
        getProtocolSession().writeFrame(streamId, frame, callback);
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
