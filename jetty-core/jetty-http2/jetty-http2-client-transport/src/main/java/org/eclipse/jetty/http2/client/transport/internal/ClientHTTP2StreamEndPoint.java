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

package org.eclipse.jetty.http2.client.transport.internal;

import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.HTTP2Channel;
import org.eclipse.jetty.http2.HTTP2Stream;
import org.eclipse.jetty.http2.HTTP2StreamEndPoint;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.thread.Invocable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClientHTTP2StreamEndPoint extends HTTP2StreamEndPoint implements HTTP2Channel.Client
{
    private static final Logger LOG = LoggerFactory.getLogger(ClientHTTP2StreamEndPoint.class);

    public ClientHTTP2StreamEndPoint(HTTP2Stream stream)
    {
        super(stream);
    }

    @Override
    public Runnable onHeaders(Stream stream, HeadersFrame frame, Callback callback)
    {
        callback.succeeded();
        return null;
    }

    @Override
    public Runnable onDataAvailable()
    {
        // The InvocationType may change depending on the read callback.
        return new Invocable.ReadyTask(getInvocationType(), this::processDataAvailable);
    }

    @Override
    public Runnable onReset(ResetFrame frame, Callback callback)
    {
        int error = frame.getError();
        EofException failure = new EofException(ErrorCode.toString(error, "error_code_" + error));
        return onFailure(failure, callback);
    }

    @Override
    public Runnable onTimeout(TimeoutException timeout, Promise<Boolean> promise)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("idle timeout on {}", this, timeout);
        Connection connection = getConnection();
        if (connection == null)
        {
            promise.succeeded(true);
            return null;
        }
        return () ->
        {
            boolean expire = connection.onIdleExpired(timeout);
            if (expire)
            {
                processFailure(timeout);
                close(timeout);
            }
            promise.succeeded(expire);
        };
    }

    @Override
    public Runnable onFailure(Throwable failure, Callback callback)
    {
        return () ->
        {
            processFailure(failure);
            close(failure);
            callback.failed(failure);
        };
    }
}
