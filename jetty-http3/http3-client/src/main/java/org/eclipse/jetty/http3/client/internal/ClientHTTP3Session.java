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

package org.eclipse.jetty.http3.client.internal;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Queue;

import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.frames.Frame;
import org.eclipse.jetty.http3.frames.SettingsFrame;
import org.eclipse.jetty.http3.internal.ControlConnection;
import org.eclipse.jetty.http3.internal.ControlFlusher;
import org.eclipse.jetty.http3.internal.InstructionFlusher;
import org.eclipse.jetty.http3.internal.InstructionHandler;
import org.eclipse.jetty.http3.internal.StreamConnection;
import org.eclipse.jetty.http3.internal.VarLenInt;
import org.eclipse.jetty.http3.internal.generator.MessageGenerator;
import org.eclipse.jetty.http3.internal.parser.ParserListener;
import org.eclipse.jetty.http3.qpack.QpackDecoder;
import org.eclipse.jetty.http3.qpack.QpackEncoder;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.quic.client.ClientProtocolSession;
import org.eclipse.jetty.quic.client.ClientQuicSession;
import org.eclipse.jetty.quic.common.QuicStreamEndPoint;
import org.eclipse.jetty.quic.common.StreamType;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClientHTTP3Session extends ClientProtocolSession implements ParserListener
{
    private static final Logger LOG = LoggerFactory.getLogger(ClientHTTP3Session.class);

    private final QpackEncoder encoder;
    private final QpackDecoder decoder;
    private final HTTP3SessionClient apiSession;
    private final InstructionFlusher encoderInstructionFlusher;
    private final InstructionFlusher decoderInstructionFlusher;
    private final ControlFlusher controlFlusher;
    private final MessageFlusher messageFlusher;

    public ClientHTTP3Session(ClientQuicSession session, Session.Client.Listener listener, Promise<Session.Client> promise, int maxBlockedStreams, int maxResponseHeadersSize)
    {
        super(session);
        this.apiSession = new HTTP3SessionClient(this, listener, promise);

        long encoderStreamId = getQuicSession().newStreamId(StreamType.CLIENT_UNIDIRECTIONAL);
        QuicStreamEndPoint encoderEndPoint = configureInstructionEndPoint(encoderStreamId);
        this.encoderInstructionFlusher = new InstructionFlusher(session, encoderEndPoint);
        this.encoder = new QpackEncoder(new InstructionHandler(encoderInstructionFlusher), maxBlockedStreams);

        long decoderStreamId = getQuicSession().newStreamId(StreamType.CLIENT_UNIDIRECTIONAL);
        QuicStreamEndPoint decoderEndPoint = configureInstructionEndPoint(decoderStreamId);
        this.decoderInstructionFlusher = new InstructionFlusher(session, decoderEndPoint);
        this.decoder = new QpackDecoder(new InstructionHandler(decoderInstructionFlusher), maxResponseHeadersSize);

        long controlStreamId = getQuicSession().newStreamId(StreamType.CLIENT_BIDIRECTIONAL);
        QuicStreamEndPoint controlEndPoint = configureControlEndPoint(controlStreamId);
        this.controlFlusher = new ControlFlusher(session, controlEndPoint);

        this.messageFlusher = new MessageFlusher(session.getByteBufferPool(), encoder);
    }

    public QpackDecoder getQpackDecoder()
    {
        return decoder;
    }

    @Override
    public void onOpen()
    {
        initializeEncoderStream();
        initializeDecoderStream();
        initializeControlStream();
        apiSession.onOpen();
    }

    private void initializeEncoderStream()
    {
        encoderInstructionFlusher.iterate();
    }

    private void initializeDecoderStream()
    {
        decoderInstructionFlusher.iterate();
    }

    private void initializeControlStream()
    {
        // Queue a synthetic frame to send the control stream type.
        ByteBuffer buffer = ByteBuffer.allocate(VarLenInt.length(ControlConnection.STREAM_TYPE));
        VarLenInt.generate(buffer, ControlConnection.STREAM_TYPE);
        buffer.flip();
        controlFlusher.offer(new Frame.Synthetic(buffer), Callback.NOOP);

        // Queue the mandatory SETTINGS frame.
        Map<Long, Long> settings = apiSession.onPreface();
        if (settings == null)
            settings = Map.of();
        // TODO: add default settings.
        SettingsFrame frame = new SettingsFrame(settings);
        controlFlusher.offer(frame, Callback.NOOP);
        controlFlusher.iterate();
    }

    private QuicStreamEndPoint configureInstructionEndPoint(long streamId)
    {
        // This is a write-only stream, so no need to link a Connection.
        return getOrCreateStreamEndPoint(streamId, QuicStreamEndPoint::onOpen);
    }

    private QuicStreamEndPoint configureControlEndPoint(long streamId)
    {
        return getOrCreateStreamEndPoint(streamId, this::configureStreamEndPoint);
    }

    @Override
    protected void onReadable(long readableStreamId)
    {
        StreamType streamType = StreamType.from(readableStreamId);
        if (streamType == StreamType.CLIENT_BIDIRECTIONAL)
        {
            super.onReadable(readableStreamId);
        }
        else
        {
            QuicStreamEndPoint streamEndPoint = getOrCreateStreamEndPoint(readableStreamId, this::configureStreamEndPoint);
            if (LOG.isDebugEnabled())
                LOG.debug("stream {} selected endpoint for read: {}", readableStreamId, streamEndPoint);
            streamEndPoint.onReadable();
        }
    }

    private void configureStreamEndPoint(QuicStreamEndPoint endPoint)
    {
        StreamConnection connection = new StreamConnection(endPoint, getQuicSession().getExecutor(), getQuicSession().getByteBufferPool(), this);
        endPoint.setConnection(connection);
        endPoint.onOpen();
        connection.onOpen();
    }

    void writeMessageFrame(QuicStreamEndPoint endPoint, Frame frame, Callback callback)
    {
        messageFlusher.offer(endPoint, frame, callback);
        messageFlusher.iterate();
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x", getClass().getSimpleName(), hashCode());
    }

    private static class MessageFlusher extends IteratingCallback
    {
        private final AutoLock lock = new AutoLock();
        private final Queue<Entry> queue = new ArrayDeque<>();
        private final ByteBufferPool.Lease lease;
        private final MessageGenerator generator;
        private Entry entry;

        public MessageFlusher(ByteBufferPool byteBufferPool, QpackEncoder encoder)
        {
            this.lease = new ByteBufferPool.Lease(byteBufferPool);
            this.generator = new MessageGenerator(encoder);
        }

        public void offer(QuicStreamEndPoint endPoint, Frame frame, Callback callback)
        {
            try (AutoLock l = lock.lock())
            {
                queue.offer(new Entry(endPoint, frame, callback));
            }
        }

        @Override
        protected Action process()
        {
            try (AutoLock l = lock.lock())
            {
                entry = queue.poll();
                if (entry == null)
                    return Action.IDLE;
            }

            generator.generate(lease, entry.frame);

            QuicStreamEndPoint endPoint = entry.endPoint;
            endPoint.write(this, lease.getByteBuffers().toArray(ByteBuffer[]::new));
            return Action.SCHEDULED;
        }

        @Override
        public void succeeded()
        {
            lease.recycle();
            entry.callback.succeeded();
            entry = null;
            super.succeeded();
        }

        @Override
        public InvocationType getInvocationType()
        {
            return entry.callback.getInvocationType();
        }

        private static class Entry
        {
            private final QuicStreamEndPoint endPoint;
            private final Frame frame;
            private final Callback callback;

            private Entry(QuicStreamEndPoint endPoint, Frame frame, Callback callback)
            {
                this.endPoint = endPoint;
                this.frame = frame;
                this.callback = callback;
            }
        }
    }
}
