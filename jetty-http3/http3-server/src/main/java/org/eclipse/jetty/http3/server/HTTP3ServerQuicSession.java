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
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.api.server.ServerSessionListener;
import org.eclipse.jetty.http3.frames.Frame;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.frames.SettingsFrame;
import org.eclipse.jetty.http3.internal.ControlConnection;
import org.eclipse.jetty.http3.internal.ControlFlusher;
import org.eclipse.jetty.http3.internal.DecoderConnection;
import org.eclipse.jetty.http3.internal.EncoderConnection;
import org.eclipse.jetty.http3.internal.VarLenInt;
import org.eclipse.jetty.http3.internal.generator.Generator;
import org.eclipse.jetty.http3.qpack.Instruction;
import org.eclipse.jetty.http3.qpack.QpackDecoder;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.quic.common.QuicStreamEndPoint;
import org.eclipse.jetty.quic.server.ProtocolServerQuicSession;
import org.eclipse.jetty.quic.server.ServerQuicSession;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HTTP3ServerQuicSession extends ProtocolServerQuicSession implements Session
{
    private static final Logger LOG = LoggerFactory.getLogger(HTTP3ServerQuicSession.class);

    private final ServerSessionListener listener;
    private final Generator generator;
    private final QpackDecoder decoder;
    private final QuicStreamEndPoint decoderEndPoint;
    private final QuicStreamEndPoint encoderEndPoint;
    private final QuicStreamEndPoint controlEndPoint;
    private final ControlFlusher controlFlusher;

    public HTTP3ServerQuicSession(ServerQuicSession session, ServerSessionListener listener, Generator generator)
    {
        super(session);
        this.listener = listener;
        this.generator = generator;

        long decoderStreamId = getQuicSession().newServerUnidirectionalStreamId();
        decoderEndPoint = configureDecoderEndPoint(decoderStreamId);

        long encoderStreamId = getQuicSession().newServerUnidirectionalStreamId();
        encoderEndPoint = configureEncoderEndPoint(encoderStreamId);

        long controlStreamId = getQuicSession().newServerBidirectionalStreamId();
        this.controlEndPoint = configureControlEndPoint(controlStreamId);
        this.controlFlusher = new ControlFlusher(session, generator, controlEndPoint);

        // TODO: configure the maxHeaderSize
        decoder = new QpackDecoder(new QpackDecoderInstructionHandler(), 4096);
    }

    @Override
    public void onOpen()
    {
        // Queue a synthetic frame to send the control stream type.
        ByteBuffer buffer = ByteBuffer.allocate(VarLenInt.length(ControlConnection.STREAM_TYPE));
        VarLenInt.generate(buffer, ControlConnection.STREAM_TYPE);
        buffer.flip();
        controlFlusher.offer(new Frame.Synthetic(buffer), Callback.NOOP);

        // Queue the mandatory SETTINGS frame.
        Map<Long, Long> settings = listener.onPreface(this);
        if (settings == null)
            settings = Map.of();
        // TODO: add default settings.
        SettingsFrame frame = new SettingsFrame(settings);
        controlFlusher.offer(frame, Callback.NOOP);
        controlFlusher.iterate();

        process();
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

    @Override
    public CompletableFuture<Stream> newStream(HeadersFrame frame, Stream.Listener listener)
    {
        return null;
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
