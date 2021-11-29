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

package org.eclipse.jetty.http3.client.internal;

import java.util.EnumSet;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.internal.HTTP3Session;
import org.eclipse.jetty.http3.internal.HTTP3Stream;
import org.eclipse.jetty.quic.common.QuicStreamEndPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HTTP3StreamClient extends HTTP3Stream implements  Stream.Client
{
    private static final Logger LOG = LoggerFactory.getLogger(HTTP3StreamClient.class);

    private Stream.Client.Listener listener;

    public HTTP3StreamClient(HTTP3Session session, QuicStreamEndPoint endPoint, boolean local)
    {
        super(session, endPoint, local);
    }

    public Stream.Client.Listener getListener()
    {
        return listener;
    }

    public void setListener(Stream.Client.Listener listener)
    {
        this.listener = listener;
    }

    public void onResponse(HeadersFrame frame)
    {
        MetaData.Response response = (MetaData.Response)frame.getMetaData();
        boolean valid;
        if (response.getStatus() == HttpStatus.CONTINUE_100)
            valid = validateAndUpdate(EnumSet.of(FrameState.INITIAL), FrameState.CONTINUE);
        else
            valid = validateAndUpdate(EnumSet.of(FrameState.INITIAL, FrameState.CONTINUE), FrameState.HEADER);
        if (valid)
        {
            notIdle();
            notifyResponse(frame);
            updateClose(frame.isLast(), false);
        }
    }

    private void notifyResponse(HeadersFrame frame)
    {
        Stream.Client.Listener listener = getListener();
        try
        {
            if (listener != null)
                listener.onResponse(this, frame);
        }
        catch (Throwable x)
        {
            LOG.info("failure notifying listener {}", listener, x);
        }
    }

    protected void notifyDataAvailable()
    {
        Listener listener = getListener();
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
    protected boolean notifyIdleTimeout(TimeoutException timeout)
    {
        Listener listener = getListener();
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
}
