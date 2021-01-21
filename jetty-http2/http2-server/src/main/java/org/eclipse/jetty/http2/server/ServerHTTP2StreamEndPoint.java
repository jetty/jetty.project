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

package org.eclipse.jetty.http2.server;

import java.util.function.Consumer;

import org.eclipse.jetty.http2.HTTP2Channel;
import org.eclipse.jetty.http2.HTTP2StreamEndPoint;
import org.eclipse.jetty.http2.IStream;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServerHTTP2StreamEndPoint extends HTTP2StreamEndPoint implements HTTP2Channel.Server
{
    private static final Logger LOG = LoggerFactory.getLogger(ServerHTTP2StreamEndPoint.class);

    public ServerHTTP2StreamEndPoint(IStream stream)
    {
        super(stream);
    }

    @Override
    public Runnable onData(DataFrame frame, Callback callback)
    {
        offerData(frame, callback);
        return null;
    }

    @Override
    public Runnable onTrailer(HeadersFrame frame)
    {
        // We are tunnelling, so there are no trailers.
        return null;
    }

    @Override
    public boolean onTimeout(Throwable failure, Consumer<Runnable> consumer)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("idle timeout on {}: {}", this, failure);
        offerFailure(failure);
        boolean result = true;
        Connection connection = getConnection();
        if (connection != null)
            result = connection.onIdleExpired();
        consumer.accept(() -> close(failure));
        return result;
    }

    @Override
    public Runnable onFailure(Throwable failure, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("failure on {}: {}", this, failure);
        offerFailure(failure);
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
