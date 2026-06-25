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

package org.eclipse.jetty.quic.quiche;

import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.DatagramChannelEndPoint;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.thread.Scheduler;

/**
 * <p>A {@link Connection} implementation that receives and sends datagram packets via its associated {@link DatagramChannelEndPoint}.</p>
 * <p>On the client, there is one datagram endpoint for every connection initiated by the client.</p>
 * <p>On the server, there is one datagram endpoint only for all connections from all clients.</p>
 * <p>The received bytes are peeked to obtain the QUIC connection ID; each QUIC connection ID has an associated
 * {@link QuicheSession}, and the received bytes are then passed to the {@link QuicheSession} for processing.</p>
 * <p>On the receive side, one QuicheConnection <em>fans-out</em> to multiple {@link QuicheSession}s.</p>
 * <p>On the send side, many {@link QuicheSession}s <em>fan-in</em> to one QuicheConnection.</p>
 */
public abstract class QuicheConnection extends AbstractConnection
{
    private final Callback fillableCallback = new FillableCallback();
    private final Scheduler scheduler;
    private final ByteBufferPool bufferPool;

    protected QuicheConnection(Executor executor, Scheduler scheduler, ByteBufferPool bufferPool, EndPoint endPoint)
    {
        super(endPoint, executor);
        this.scheduler = scheduler;
        this.bufferPool = bufferPool;
    }

    public Scheduler getScheduler()
    {
        return scheduler;
    }

    public ByteBufferPool getByteBufferPool()
    {
        return bufferPool;
    }

    @Override
    public void fillInterested()
    {
        getEndPoint().fillInterested(fillableCallback);
    }

    public abstract void write(Callback callback, SocketAddress remoteAddress, ByteBuffer... buffers);

    @Override
    public abstract boolean onIdleExpired(TimeoutException timeoutException);

    public abstract void disconnect(QuicheSession session, Throwable failure);

    private class FillableCallback implements Callback
    {
        @Override
        public void succeeded()
        {
            onFillable();
        }

        @Override
        public void failed(Throwable x)
        {
            onFillInterestedFailed(x);
        }

        @Override
        public InvocationType getInvocationType()
        {
            // Must be EITHER so that its invocation is not deferred,
            // since this task may read from the network a flow control
            // update that would unblock stalled threads.
            return InvocationType.EITHER;
        }

        @Override
        public String toString()
        {
            return "%s@%x[%s]".formatted(TypeUtil.toShortName(getClass()), hashCode(), getInvocationType());
        }
    }
}
