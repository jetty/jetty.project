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

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.HTTP3ErrorCode;
import org.eclipse.jetty.http3.HTTP3Session;
import org.eclipse.jetty.http3.HTTP3Stream;
import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.frames.Frame;
import org.eclipse.jetty.http3.frames.GoAwayFrame;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.frames.SettingsFrame;
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
    protected HTTP3StreamServer newHTTP3Stream(QuicStreamEndPoint endPoint, boolean local)
    {
        return new HTTP3StreamServer(this, endPoint, local);
    }

    @Override
    public void onHeaders(long streamId, HeadersFrame frame, boolean wasBlocked)
    {
        MetaData metaData = frame.getMetaData();
        if (metaData.isRequest())
        {
            QuicStreamEndPoint endPoint = getProtocolSession().getStreamEndPoint(streamId);
            HTTP3StreamServer stream = (HTTP3StreamServer)getOrCreateStream(endPoint);
            if (LOG.isDebugEnabled())
                LOG.debug("received request {} on {}", frame, stream);
            if (stream != null)
            {
                Throwable metaDataFailure = MetaData.Failed.getFailure(metaData);
                if (metaDataFailure != null)
                {
                    // It's a bad request, request content will be dropped.
                    stream.updateClose(true, false);
                    notifyStreamFailure(stream, new BadMessageException(metaDataFailure.getMessage(), metaDataFailure));
                }
                else
                {
                    stream.onRequest(frame);
                }
            }
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
    public void writeMessageFrame(long streamId, Frame frame, Callback callback)
    {
        getProtocolSession().writeMessageFrame(streamId, frame, callback);
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

    private void notifyStreamFailure(Stream stream, Throwable failure)
    {
        Server.Listener listener = getListener();
        if (listener instanceof Listener l)
        {
            try
            {
                l.onStreamFailure(stream, failure);
            }
            catch (Throwable x)
            {
                LOG.info("failure notifying listener {}", listener, x);
            }
        }
        else
        {
            ((HTTP3Stream)stream).notifyFailure(HTTP3ErrorCode.REQUEST_CANCELLED_ERROR.code(), failure);
        }
    }

    public interface Listener extends Session.Server.Listener
    {
        void onStreamFailure(Stream stream, Throwable failure);
    }
}
