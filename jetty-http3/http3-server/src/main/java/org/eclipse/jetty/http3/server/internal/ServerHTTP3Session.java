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

package org.eclipse.jetty.http3.server.internal;

import java.util.Map;

import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.frames.SettingsFrame;
import org.eclipse.jetty.http3.internal.ControlFlusher;
import org.eclipse.jetty.http3.internal.InstructionFlusher;
import org.eclipse.jetty.http3.internal.InstructionHandler;
import org.eclipse.jetty.http3.internal.StreamConnection;
import org.eclipse.jetty.http3.internal.generator.MessageGenerator;
import org.eclipse.jetty.http3.qpack.QpackDecoder;
import org.eclipse.jetty.http3.qpack.QpackEncoder;
import org.eclipse.jetty.quic.common.QuicStreamEndPoint;
import org.eclipse.jetty.quic.common.StreamType;
import org.eclipse.jetty.quic.server.ServerProtocolSession;
import org.eclipse.jetty.quic.server.ServerQuicSession;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServerHTTP3Session extends ServerProtocolSession
{
    private static final Logger LOG = LoggerFactory.getLogger(ServerHTTP3Session.class);

    private final QpackEncoder encoder;
    private final QpackDecoder decoder;
    private final HTTP3SessionServer apiSession;
    private final InstructionFlusher encoderFlusher;
    private final InstructionFlusher decoderFlusher;
    private final ControlFlusher controlFlusher;
    private final MessageGenerator generator;

    public ServerHTTP3Session(ServerQuicSession session, Session.Server.Listener listener, int maxBlockedStreams, int maxRequestHeadersSize)
    {
        super(session);
        this.apiSession = new HTTP3SessionServer(this, listener);

        long encoderStreamId = getQuicSession().newStreamId(StreamType.SERVER_UNIDIRECTIONAL);
        QuicStreamEndPoint encoderEndPoint = configureEncoderEndPoint(encoderStreamId);
        this.encoderFlusher = new InstructionFlusher(session, encoderEndPoint);
        this.encoder = new QpackEncoder(new InstructionHandler(encoderFlusher), maxBlockedStreams);

        long decoderStreamId = getQuicSession().newStreamId(StreamType.SERVER_UNIDIRECTIONAL);
        QuicStreamEndPoint decoderEndPoint = configureDecoderEndPoint(decoderStreamId);
        this.decoderFlusher = new InstructionFlusher(session, decoderEndPoint);
        this.decoder = new QpackDecoder(new InstructionHandler(decoderFlusher), maxRequestHeadersSize);

        // TODO: make parameters configurable.
        this.generator = new MessageGenerator(encoder, 4096, true);
        long controlStreamId = getQuicSession().newStreamId(StreamType.SERVER_UNIDIRECTIONAL);
        QuicStreamEndPoint controlEndPoint = configureControlEndPoint(controlStreamId);
        this.controlFlusher = new ControlFlusher(session, controlEndPoint);
    }

    public QpackDecoder getQpackDecoder()
    {
        return decoder;
    }

    public HTTP3SessionServer getSessionServer()
    {
        return apiSession;
    }

    @Override
    public void onOpen()
    {
        // Queue the mandatory SETTINGS frame.
        Map<Long, Long> settings = apiSession.onPreface();
        if (settings == null)
            settings = Map.of();
        // TODO: add default settings.
        SettingsFrame frame = new SettingsFrame(settings);
        controlFlusher.offer(frame, Callback.NOOP);
        controlFlusher.iterate();
    }

    private QuicStreamEndPoint configureEncoderEndPoint(long streamId)
    {
        // This is a write-only stream, so no need to link a Connection.
        return getOrCreateStreamEndPoint(streamId, QuicStreamEndPoint::onOpen);
    }

    private QuicStreamEndPoint configureDecoderEndPoint(long streamId)
    {
        // This is a write-only stream, so no need to link a Connection.
        return getOrCreateStreamEndPoint(streamId, QuicStreamEndPoint::onOpen);
    }

    private QuicStreamEndPoint configureControlEndPoint(long streamId)
    {
        // This is a write-only stream, so no need to link a Connection.
        return getOrCreateStreamEndPoint(streamId, QuicStreamEndPoint::onOpen);
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
            // On the server, we need a get-or-create semantic in case of reads.
            QuicStreamEndPoint streamEndPoint = getOrCreateStreamEndPoint(readableStreamId, this::configureStreamEndPoint);
            if (LOG.isDebugEnabled())
                LOG.debug("stream {} selected endpoint for read: {}", readableStreamId, streamEndPoint);
            streamEndPoint.onReadable();
        }
    }

    private void configureStreamEndPoint(QuicStreamEndPoint endPoint)
    {
        StreamConnection connection = new StreamConnection(endPoint, getQuicSession().getExecutor(), getQuicSession().getByteBufferPool(), apiSession);
        endPoint.setConnection(connection);
        endPoint.onOpen();
        connection.onOpen();
    }
}
