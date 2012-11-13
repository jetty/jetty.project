//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.proxy;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Callback;

public class DownstreamConnection extends ProxyConnection
{
    private final ByteBuffer buffer;

    public DownstreamConnection(EndPoint endPoint, Executor executor, ByteBufferPool bufferPool, ConcurrentMap<String, Object> context, ConnectHandler connectHandler, ByteBuffer buffer)
    {
        super(endPoint, executor, bufferPool, context, connectHandler);
        this.buffer = buffer;
    }

    @Override
    public void onOpen()
    {
        super.onOpen();
        final int remaining = buffer.remaining();
        write(buffer, new Callback<Void>()
        {
            @Override
            public void completed(Void context)
            {
                LOG.debug("{} wrote initial {} bytes to server", DownstreamConnection.this, remaining);
                fillInterested();
            }

            @Override
            public void failed(Void context, Throwable x)
            {
                LOG.debug(this + " failed to write initial " + remaining + " bytes to server", x);
                close();
                getConnection().close();
            }
        });
    }
}
