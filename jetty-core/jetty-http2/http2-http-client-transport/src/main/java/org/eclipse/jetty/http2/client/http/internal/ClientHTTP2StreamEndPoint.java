//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http2.client.http.internal;

import org.eclipse.jetty.http2.internal.HTTP2Channel;
import org.eclipse.jetty.http2.internal.HTTP2Stream;
import org.eclipse.jetty.http2.internal.HTTP2StreamEndPoint;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.util.Callback;
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
    public void onDataAvailable()
    {
        processDataAvailable();
    }

    @Override
    public boolean onTimeout(Throwable failure)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("idle timeout on {}: {}", this, failure);
        Connection connection = getConnection();
        if (connection != null)
            return connection.onIdleExpired();
        return true;
    }

    @Override
    public void onFailure(Throwable failure, Callback callback)
    {
        callback.failed(failure);
    }
}
