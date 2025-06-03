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

import java.util.EnumSet;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.http3.HTTP3Session;
import org.eclipse.jetty.http3.HTTP3Stream;
import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.quic.common.StreamEndPoint;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.thread.Invocable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HTTP3StreamServer extends HTTP3Stream implements Stream.Server, Invocable
{
    private static final Logger LOG = LoggerFactory.getLogger(HTTP3StreamServer.class);
    private static final Listener DEFAULT_LISTENER = new Listener() {};

    private Stream.Server.Listener listener;

    public HTTP3StreamServer(HTTP3Session session, StreamEndPoint endPoint, boolean local)
    {
        super(session, endPoint, local);
    }

    private Listener getListener()
    {
        return listener;
    }

    public void onRequest(HeadersFrame frame)
    {
        validateAndUpdate(EnumSet.of(FrameState.INITIAL), FrameState.HEADER);
        onHeaders(frame);
        updateClose(frame.isLast(), false);
        listener = notifyRequest(frame);
    }

    private Listener notifyRequest(HeadersFrame frame)
    {
        Session.Server.Listener listener = (Session.Server.Listener)getSession().getListener();
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

    @Override
    public void respond(HeadersFrame frame, Promise.Invocable<Stream> promise)
    {
        write(frame, promise);
    }

    protected void notifyDataAvailable()
    {
        Stream.Server.Listener listener = Objects.requireNonNullElse(getListener(), DEFAULT_LISTENER);
        try
        {
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
        Listener listener = getListener();
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
    protected void notifyIdleTimeout(TimeoutException timeout, Promise<Boolean> promise)
    {
        Listener listener = getListener();
        try
        {
            if (listener != null)
                listener.onIdleTimeout(this, timeout, promise);
            else
                promise.succeeded(true);
        }
        catch (Throwable x)
        {
            LOG.info("failure notifying listener {}", listener, x);
            promise.failed(x);
        }
    }

    @Override
    public void notifyFailure(long error, Throwable failure)
    {
        Listener listener = getListener();
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

    @Override
    public InvocationType getInvocationType()
    {
        return Invocable.getInvocationType(getListener());
    }
}
