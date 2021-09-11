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

package org.eclipse.jetty.http3.server;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import org.eclipse.jetty.http3.internal.ControlConnection;
import org.eclipse.jetty.http3.internal.DecoderConnection;
import org.eclipse.jetty.http3.internal.EncoderConnection;
import org.eclipse.jetty.http3.qpack.Instruction;
import org.eclipse.jetty.http3.qpack.QpackDecoder;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.quic.common.QuicStreamEndPoint;
import org.eclipse.jetty.quic.server.ProtocolServerQuicSession;
import org.eclipse.jetty.quic.server.ServerQuicSession;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.thread.AutoLock;

public class HTTP3ServerQuicSession extends ProtocolServerQuicSession
{
    private final QpackDecoder decoder;
    private QuicStreamEndPoint decoderEndPoint;
    private QuicStreamEndPoint encoderEndPoint;
    private QuicStreamEndPoint controlEndPoint;

    public HTTP3ServerQuicSession(ServerQuicSession session, int maxHeaderSize)
    {
        super(session);
        decoder = new QpackDecoder(new QpackDecoderInstructionHandler(), maxHeaderSize);
    }

    @Override
    public void onOpen()
    {
        long decoderStreamId = getQuicSession().newServerUnidirectionalStreamId();
        decoderEndPoint = configureDecoderEndPoint(decoderStreamId);
        
        long encoderStreamId = getQuicSession().newServerUnidirectionalStreamId();
        encoderEndPoint = configureEncoderEndPoint(encoderStreamId);

        long controlStreamId = getQuicSession().newServerBidirectionalStreamId();
        controlEndPoint = configureControlEndPoint(controlStreamId);
    }

    private QuicStreamEndPoint configureDecoderEndPoint(long streamId)
    {
        return getOrCreateStreamEndPoint(streamId, endPoint ->
        {
            DecoderConnection connection = new DecoderConnection(endPoint, getQuicSession().getExecutor());
            endPoint.setConnection(connection);
            endPoint.onOpen();
            connection.onOpen();
        });
    }

    private QuicStreamEndPoint configureEncoderEndPoint(long streamId)
    {
        return getOrCreateStreamEndPoint(streamId, endPoint ->
        {
            EncoderConnection connection = new EncoderConnection(endPoint, getQuicSession().getExecutor());
            endPoint.setConnection(connection);
            endPoint.onOpen();
            connection.onOpen();
        });
    }

    private QuicStreamEndPoint configureControlEndPoint(long streamId)
    {
        return getOrCreateStreamEndPoint(streamId, endPoint ->
        {
            ControlConnection connection = new ControlConnection(endPoint, getQuicSession().getExecutor());
            endPoint.setConnection(connection);
            endPoint.onOpen();
            connection.onOpen();
        });
    }

    public QpackDecoder getQpackDecoder()
    {
        return decoder;
    }

    private class QpackDecoderInstructionHandler extends IteratingCallback implements Instruction.Handler
    {
        private final AutoLock lock = new AutoLock();
        private final ByteBufferPool.Lease lease = new ByteBufferPool.Lease(getQuicSession().getByteBufferPool());
        private final Queue<Instruction> queue = new ArrayDeque<>();

        @Override
        public void onInstructions(List<Instruction> instructions)
        {
            try (AutoLock l = lock.lock())
            {
                queue.addAll(instructions);
            }
            iterate();
        }

        @Override
        protected Action process()
        {
            List<Instruction> instructions;
            try (AutoLock l = lock.lock())
            {
                if (queue.isEmpty())
                    return Action.IDLE;
                instructions = new ArrayList<>(queue);
            }
            instructions.forEach(i -> i.encode(lease));
            decoderEndPoint.write(this, getQuicSession().getRemoteAddress(), lease.getByteBuffers().toArray(ByteBuffer[]::new));
            return Action.SCHEDULED;
        }

        @Override
        public void succeeded()
        {
            lease.recycle();
            super.succeeded();
        }

        @Override
        public InvocationType getInvocationType()
        {
            return InvocationType.NON_BLOCKING;
        }
    }
}
