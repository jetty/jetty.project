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

import java.util.EnumSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.internal.HTTP3ErrorCode;
import org.eclipse.jetty.http3.internal.HTTP3Session;
import org.eclipse.jetty.http3.internal.HTTP3Stream;
import org.eclipse.jetty.http3.internal.MessageFlusher;
import org.eclipse.jetty.quic.common.QuicStreamEndPoint;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Invocable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HTTP3StreamServer extends HTTP3Stream implements Stream.Server
{
    private static final Logger LOG = LoggerFactory.getLogger(HTTP3StreamServer.class);

    private Stream.Server.Listener listener;

    public HTTP3StreamServer(HTTP3Session session, QuicStreamEndPoint endPoint, boolean local)
    {
        super(session, endPoint, local);
    }

    public void onRequest(HeadersFrame frame)
    {
        if (validateAndUpdate(EnumSet.of(FrameState.INITIAL), FrameState.HEADER))
        {
            notIdle();
            Listener listener = this.listener = notifyRequest(frame);
            if (listener == null)
            {
                QuicStreamEndPoint endPoint = getEndPoint();
                Callback callback = Callback.from(Invocable.InvocationType.NON_BLOCKING, () -> endPoint.shutdownInput(HTTP3ErrorCode.NO_ERROR.code()));
                getSession().writeMessageFrame(getId(), new MessageFlusher.FlushFrame(), callback);
            }
            updateClose(frame.isLast(), false);
        }
    }

    private Listener notifyRequest(HeadersFrame frame)
    {
        Session.Server.Listener listener = (Session.Server.Listener)getSession().getListener();
        try
        {
            return listener.onRequest(this, frame);
        }
        catch (Throwable x)
        {
            LOG.info("failure notifying listener {}", listener, x);
            return null;
        }
    }

    @Override
    public CompletableFuture<Stream> respond(HeadersFrame frame)
    {
        return write(frame);
    }

    protected void notifyDataAvailable()
    {
        Stream.Server.Listener listener = this.listener;
        try
        {
            if (listener != null)
                listener.onDataAvailable(this);
        }
        catch (Throwable x)
        {
            LOG.info("failure notifying listener {}", listener, x);
        }
    }

    @Override
    protected void notifyTrailer(HeadersFrame frame)
    {
        Listener listener = this.listener;
        try
        {
            if (listener != null)
                listener.onTrailer(this, frame);
        }
        catch (Throwable x)
        {
            LOG.info("failure notifying listener {}", listener, x);
        }
    }

    @Override
    protected boolean notifyIdleTimeout(TimeoutException timeout)
    {
        Listener listener = this.listener;
        try
        {
            if (listener != null)
                return listener.onIdleTimeout(this, timeout);
            return true;
        }
        catch (Throwable x)
        {
            LOG.info("failure notifying listener {}", listener, x);
            return true;
        }
    }

    @Override
    protected void notifyFailure(long error, Throwable failure)
    {
        Listener listener = this.listener;
        try
        {
            if (listener != null)
                listener.onFailure(this, error, failure);
        }
        catch (Throwable x)
        {
            LOG.info("failure notifying listener {}", listener, x);
        }
    }
}
