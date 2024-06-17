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

package org.eclipse.jetty.http2.server.internal;

import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;

import org.eclipse.jetty.http2.HTTP2Channel;
import org.eclipse.jetty.http2.HTTP2Stream;
import org.eclipse.jetty.http2.HTTP2StreamEndPoint;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServerHTTP2StreamEndPoint extends HTTP2StreamEndPoint implements HTTP2Channel.Server
{
    private static final Logger LOG = LoggerFactory.getLogger(ServerHTTP2StreamEndPoint.class);

    public ServerHTTP2StreamEndPoint(HTTP2Stream stream)
    {
        super(stream);
    }

    @Override
    public Runnable onDataAvailable()
    {
        processDataAvailable();
        return null;
    }

    @Override
    public Runnable onTrailer(HeadersFrame frame)
    {
        // We are tunnelling, so there are no trailers.
        return null;
    }

    @Override
    public void onTimeout(TimeoutException timeout, BiConsumer<Runnable, Boolean> consumer)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("idle timeout on {}", this, timeout);
        boolean result = true;
        Connection connection = getConnection();
        if (connection != null)
            result = connection.onIdleExpired(timeout);
        Runnable r = null;
        if (result)
        {
            processFailure(timeout);
            r = () -> close(timeout);
        }
        consumer.accept(r, result);
    }

    @Override
    public Runnable onFailure(Throwable failure, boolean remote, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{}failure on {}", remote ? "remote " : "", this, failure);
        processFailure(failure);
        close(failure);
        return callback::succeeded;
    }

    @Override
    public boolean isIdle()
    {
        // We are tunnelling, so we are never idle.
        return false;
    }
}
