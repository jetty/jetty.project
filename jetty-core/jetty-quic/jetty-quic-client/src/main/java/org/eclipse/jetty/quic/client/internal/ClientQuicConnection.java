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

package org.eclipse.jetty.quic.client.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.SocketAddress;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.frames.ConnectionCloseFrame;
import org.eclipse.jetty.quic.client.QuicClientQuicConfiguration;
import org.eclipse.jetty.quic.client.internal.tls.ClientTLSEngine;
import org.eclipse.jetty.quic.common.CongestionController;
import org.eclipse.jetty.quic.common.PacketTracker;
import org.eclipse.jetty.quic.common.QuicConnection;
import org.eclipse.jetty.quic.common.QuicSession;
import org.eclipse.jetty.quic.common.packets.PacketNumbers;
import org.eclipse.jetty.quic.common.packets.PacketProtector;
import org.eclipse.jetty.quic.common.tls.generator.QuicMessagesGenerator;
import org.eclipse.jetty.quic.util.ErrorCode;
import org.eclipse.jetty.tls.common.TranscriptHash;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.component.LifeCycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// The client-specific implementation of [QuicConnection].
///
/// There is one instance of this class for each `DatagramChannel`
/// opened by the client to connect to a server.
///
/// This class has therefore a 1-to-1 relationship with a
/// [ClientQuicSession].
public class ClientQuicConnection extends QuicConnection implements Callback
{
    private static final Logger LOG = LoggerFactory.getLogger(ClientQuicConnection.class);

    private final AtomicLong bytesIn = new AtomicLong();
    private final ClientConnector connector;
    private final QuicClientQuicConfiguration quicConfiguration;
    private final Map<String, Object> context;
    private ClientQuicSession session;

    public ClientQuicConnection(ClientConnector connector, QuicClientQuicConfiguration quicConfiguration, EndPoint endPoint, Map<String, Object> context)
    {
        super(connector.getExecutor(), connector.getScheduler(), connector.getByteBufferPool(), endPoint);
        this.connector = connector;
        this.quicConfiguration = quicConfiguration;
        this.context = context;
    }

    public QuicClientQuicConfiguration getClientQuicConfiguration()
    {
        return quicConfiguration;
    }

    @Override
    public void onOpen()
    {
        CongestionController congestionController = getClientQuicConfiguration().getCongestionControllerFactory().newCongestionController();
        PacketTracker packetTracker = new PacketTracker(getScheduler(), congestionController);
        packetTracker.setAcknowledgmentMaxDelay(quicConfiguration.getAcknowledgmentMaxDelay());
        packetTracker.setAcknowledgmentDelayExponent(quicConfiguration.getAcknowledgmentDelayExponent());
        PacketNumbers packetNumbers = new PacketNumbers();
        ByteBufferPool byteBufferPool = getByteBufferPool();
        TranscriptHash transcriptHash = new TranscriptHash(byteBufferPool, new QuicMessagesGenerator(byteBufferPool, false), new QuicMessagesGenerator(byteBufferPool, true));
        PacketProtector protector = new PacketProtector(byteBufferPool, packetNumbers, transcriptHash, true);
        ClientTLSEngine tlsEngine = new ClientTLSEngine(protector);
        session = new ClientQuicSession(connector, quicConfiguration, this, packetTracker, packetNumbers, tlsEngine, context);
        session.setIdleTimeout(getEndPoint().getIdleTimeout());
        LifeCycle.start(session);
        session.connect(this);
    }

    @Override
    public void succeeded()
    {
        super.onOpen();
    }

    @Override
    public void failed(Throwable x)
    {
        session.fail(x);
    }

    @Override
    public long getBytesIn()
    {
        return bytesIn.get();
    }

    @Override
    public long getBytesOut()
    {
        // TODO
        return 0;
    }

    @Override
    public boolean onIdleExpired(TimeoutException timeoutException)
    {
        session.onIdleTimeout(timeoutException);
        return false;
    }

    @Override
    protected Runnable doProduce()
    {
        RetainableByteBuffer.Mutable buffer = getByteBufferPool().acquire(getInputBufferSize(), isUseInputDirectByteBuffers());
        try
        {
            while (true)
            {
                SocketAddress address = getEndPoint().receive(buffer.getByteBuffer());
                int filled = address == EndPoint.EOF ? -1 : buffer.remaining();
                if (LOG.isDebugEnabled())
                    LOG.debug("filled {} bytes from {} on {}", filled, address, this);

                if (filled < 0)
                {
                    buffer.release();
                    getEndPoint().shutdownOutput();
                    return null;
                }
                if (filled == 0)
                {
                    buffer.release();
                    fillInterested();
                    return null;
                }

                bytesIn.addAndGet(filled);
                session.process(address, buffer);

                Task task = pollTask();
                if (LOG.isDebugEnabled())
                    LOG.debug("produced task {} on {}", task, this);
                if (task == null)
                    continue;

                buffer.release();
                return task;
            }
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("failed to produce on {}", this, x);
            buffer.release();
            // TODO
            // fail(x);
            return null;
        }
    }

    @Override
    public void close()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("closing {}", this);

        QuicSession quicSession = session;
        if (quicSession != null)
        {
            // This method has blocking semantic.
            try (Blocker.Promise<Void> blocker = Blocker.promise())
            {
                ConnectionCloseFrame frame = new ConnectionCloseFrame(ErrorCode.NO_ERROR.code(), "close", 0x00);
                // Propagate upwards.
                quicSession.close(frame, Promise.Invocable.toPromise(blocker, _ -> null));
                blocker.block();
            }
            catch (IOException x)
            {
                throw new UncheckedIOException(x);
            }
        }
        else
        {
            super.close();
        }
    }

    @Override
    protected void terminate(QuicSession session)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("terminate {} on {}", session, this);
        assert this.session == session;
        getEndPoint().close();
        LifeCycle.stop(session);
    }
}
