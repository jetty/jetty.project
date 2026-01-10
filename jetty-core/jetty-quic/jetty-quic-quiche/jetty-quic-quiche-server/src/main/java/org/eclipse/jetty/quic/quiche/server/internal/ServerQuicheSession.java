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

package org.eclipse.jetty.quic.quiche.server.internal;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLHandshakeException;

import org.eclipse.jetty.io.CyclicTimeouts;
import org.eclipse.jetty.quic.api.Session;
import org.eclipse.jetty.quic.api.frames.ConnectionCloseFrame;
import org.eclipse.jetty.quic.common.StreamId;
import org.eclipse.jetty.quic.quiche.Quiche;
import org.eclipse.jetty.quic.quiche.QuicheSession;
import org.eclipse.jetty.quic.quiche.server.QuicheServerQuicConfiguration;
import org.eclipse.jetty.quic.util.ErrorCode;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.thread.Invocable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>The server specific implementation of {@link QuicheSession}.</p>
 */
public class ServerQuicheSession extends QuicheSession implements CyclicTimeouts.Expirable
{
    private static final Logger LOG = LoggerFactory.getLogger(ServerQuicheSession.class);

    private final StreamsProducerTask producer = new StreamsProducerTask();
    private final AtomicLong streamIds = new AtomicLong();
    private long expireNanoTime = Long.MAX_VALUE;
    private SocketAddress remoteAddress;

    public ServerQuicheSession(Connector connector, QuicheServerQuicConfiguration configuration, Quiche quiche, ServerQuicheConnection connection, SocketAddress localAddress, SocketAddress remoteAddress, Session.Listener listener)
    {
        super(connector.getExecutor(), connector.getScheduler(), connector.getByteBufferPool(), configuration, quiche, connection, localAddress, remoteAddress, listener);
    }

    @Override
    public QuicheServerQuicConfiguration getQuicConfiguration()
    {
        return (QuicheServerQuicConfiguration)super.getQuicConfiguration();
    }

    @Override
    protected ServerQuicheConnection getConnection()
    {
        return (ServerQuicheConnection)super.getConnection();
    }

    @Override
    public SocketAddress getRemoteSocketAddress()
    {
        return remoteAddress;
    }

    @Override
    public void setIdleTimeout(long idleTimeout)
    {
        super.setIdleTimeout(idleTimeout);
        notIdle();
        getConnection().schedule(this);
    }

    @Override
    public boolean onIdleTimeout(TimeoutException timeout)
    {
        boolean result = super.onIdleTimeout(timeout);
        if (!result)
            notIdle();
        return result;
    }

    @Override
    public long newStreamId(boolean bidirectional)
    {
        return StreamId.newStreamId(streamIds.getAndIncrement(), bidirectional, false);
    }

    @Override
    public long getExpireNanoTime()
    {
        return expireNanoTime;
    }

    Runnable process(SocketAddress remoteAddress, ByteBuffer cipherBuffer)
    {
        try
        {
            feed(remoteAddress, cipherBuffer);

            if (isConnectionEstablished())
            {
                if (!isOpen())
                {
                    if (!validateConnection())
                        return null;
                    open();
                    if (LOG.isDebugEnabled())
                        LOG.debug("opened {}", this);
                }
                // Return a task because we want 1 thread per active session.
                return getProducerTask();
            }
            else
            {
                flush();
                return null;
            }
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("process failure for {}", this, x);
            ConnectionCloseFrame frame = new ConnectionCloseFrame(ErrorCode.CONNECTION_REFUSED_ERROR.code(), "session_failure");
            disconnect(frame, x, Promise.Invocable.noop());
            return null;
        }
    }

    private boolean validateConnection()
    {
        if (getConnection().getSslContextFactory().getNeedClientAuth() && getPeerCertificates() == null)
        {
            ConnectionCloseFrame frame = new ConnectionCloseFrame(ErrorCode.CONNECTION_REFUSED_ERROR.code(), "missing_peer_certificates");
            disconnect(frame, new SSLHandshakeException(frame.reason()), Promise.Invocable.noop());
            return false;
        }
        return true;
    }

    @Override
    public void feed(SocketAddress remoteAddress, ByteBuffer cipherBuffer) throws IOException
    {
        // While the connection ID remains the same,
        // the remote address may change so store it again.
        this.remoteAddress = remoteAddress;
        notIdle();
        super.feed(remoteAddress, cipherBuffer);
    }

    Runnable getProducerTask()
    {
        return producer;
    }

    @Override
    public void flush()
    {
        notIdle();
        super.flush();
    }

    private void notIdle()
    {
        expireNanoTime = CyclicTimeouts.Expirable.calcExpireNanoTime(getIdleTimeout());
    }

    private class StreamsProducerTask extends Invocable.Task.Abstract
    {
        private StreamsProducerTask()
        {
            // Must be EITHER so that its invocation is not deferred,
            // since this task may process a stream that would unblock
            // stalled threads.
            // NON_BLOCKING could have worked too, but EITHER provides
            // parallelization of session processing which is a plus.
            super(InvocationType.EITHER);
        }

        @Override
        public void run()
        {
            produce();
        }

        @Override
        public String toString()
        {
            return "%s@%x".formatted(TypeUtil.toShortName(getClass()), hashCode());
        }
    }
}
