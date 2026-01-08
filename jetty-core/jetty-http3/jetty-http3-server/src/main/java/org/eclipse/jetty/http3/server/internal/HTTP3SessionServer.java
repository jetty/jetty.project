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

import org.eclipse.jetty.http.HttpException;
import org.eclipse.jetty.http.HttpStatus;
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
import org.eclipse.jetty.http3.qpack.QpackDecoder;
import org.eclipse.jetty.http3.qpack.QpackEncoder;
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

    public QpackEncoder getQpackEncoder()
    {
        return getServerHTTP3Session().getQpackEncoder();
    }

    public QpackDecoder getQpackDecoder()
    {
        return getServerHTTP3Session().getQpackDecoder();
    }

    private ServerHTTP3Session getServerHTTP3Session()
    {
        return (ServerHTTP3Session)getProtocolSession();
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
        MetaData metaData = frame.getMetaData();
        if (metaData.isRequest())
        {
            StreamEndPoint endPoint = getProtocolSession().getStreamEndPoint(streamId);
            HTTP3StreamServer stream = (HTTP3StreamServer)createStream(endPoint);
            if (LOG.isDebugEnabled())
                LOG.debug("received request {} on {}", frame, stream);
            Throwable metaDataFailure = MetaData.Failed.getFailure(metaData);
            if (metaDataFailure != null)
            {
                // It's a bad request, request content will be dropped.
                stream.updateClose(true, false);
                notifyStreamFailure(stream, new HttpException.RuntimeException(HttpStatus.BAD_REQUEST_400, metaDataFailure.getMessage(), metaDataFailure));
            }
            else
            {
                stream.onRequest(frame);
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
        getServerHTTP3Session().onSettings(frame);
        super.onSettings(frame);
    }

    @Override
    public void writeControlFrame(Frame frame, Callback callback)
    {
        getServerHTTP3Session().writeControlFrame(frame, callback);
    }

    @Override
    public void writeMessageFrame(StreamEndPoint streamEndPoint, Frame frame, Callback callback)
    {
        getServerHTTP3Session().writeMessageFrame(streamEndPoint, frame, callback);
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
            if (listener != null)
                listener.onAccept(this);
        }
        catch (Throwable x)
        {
            LOG.info("failure notifying listener {}", listener, x);
        }
    }

    Stream.Server.Listener notifyRequest(HeadersFrame frame)
    {
        Server.Listener listener = getListener();
        try
        {
            if (listener != null)
                return listener.onRequest(this, frame);
            return null;
        }
        catch (Throwable x)
        {
            LOG.info("failure notifying listener {}", listener, x);
            return null;
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
