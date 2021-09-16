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
import org.eclipse.jetty.http3.frames.Frame;
import org.eclipse.jetty.http3.frames.SettingsFrame;
import org.eclipse.jetty.http3.internal.ControlFlusher;
import org.eclipse.jetty.http3.internal.DecoderConnection;
import org.eclipse.jetty.http3.internal.EncoderConnection;
import org.eclipse.jetty.http3.internal.HTTP3Flusher;
import org.eclipse.jetty.http3.internal.InstructionFlusher;
import org.eclipse.jetty.http3.internal.InstructionHandler;
import org.eclipse.jetty.http3.internal.UnidirectionalStreamConnection;
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
    private final HTTP3Flusher messageFlusher;

    public ServerHTTP3Session(ServerQuicSession session, Session.Server.Listener listener, int maxBlockedStreams, int maxRequestHeadersSize)
    {
        super(session);
        this.apiSession = new HTTP3SessionServer(this, listener);

        if (LOG.isDebugEnabled())
            LOG.debug("initializing HTTP/3 streams");

        long encoderStreamId = getQuicSession().newStreamId(StreamType.SERVER_UNIDIRECTIONAL);
        QuicStreamEndPoint encoderEndPoint = configureEncoderEndPoint(encoderStreamId);
        this.encoderFlusher = new InstructionFlusher(session, encoderEndPoint, EncoderConnection.STREAM_TYPE);
        this.encoder = new QpackEncoder(new InstructionHandler(encoderFlusher), maxBlockedStreams);
        if (LOG.isDebugEnabled())
            LOG.debug("created encoder stream #{} on {}", encoderStreamId, encoderEndPoint);

        long decoderStreamId = getQuicSession().newStreamId(StreamType.SERVER_UNIDIRECTIONAL);
        QuicStreamEndPoint decoderEndPoint = configureDecoderEndPoint(decoderStreamId);
        this.decoderFlusher = new InstructionFlusher(session, decoderEndPoint, DecoderConnection.STREAM_TYPE);
        this.decoder = new QpackDecoder(new InstructionHandler(decoderFlusher), maxRequestHeadersSize);
        if (LOG.isDebugEnabled())
            LOG.debug("created decoder stream #{} on {}", decoderStreamId, decoderEndPoint);

        // TODO: make parameters configurable.
        this.generator = new MessageGenerator(encoder, 4096, true);
        long controlStreamId = getQuicSession().newStreamId(StreamType.SERVER_UNIDIRECTIONAL);
        QuicStreamEndPoint controlEndPoint = configureControlEndPoint(controlStreamId);
        this.controlFlusher = new ControlFlusher(session, controlEndPoint);
        if (LOG.isDebugEnabled())
            LOG.debug("created control stream #{} on {}", controlStreamId, controlEndPoint);

        // TODO: make parameters configurable.
        this.messageFlusher = new HTTP3Flusher(session.getByteBufferPool(), encoder, 4096, true);
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
            if (LOG.isDebugEnabled())
                LOG.debug("stream #{} selected for read", readableStreamId);
            super.onReadable(readableStreamId);
        }
        else
        {
            // On the server, we need a get-or-create semantic in case of reads.
            QuicStreamEndPoint streamEndPoint = getOrCreateStreamEndPoint(readableStreamId, this::configureUnidirectionalStreamEndPoint);
            if (LOG.isDebugEnabled())
                LOG.debug("stream #{} selected for read: {}", readableStreamId, streamEndPoint);
            streamEndPoint.onReadable();
        }
    }

    private void configureUnidirectionalStreamEndPoint(QuicStreamEndPoint endPoint)
    {
        UnidirectionalStreamConnection connection = new UnidirectionalStreamConnection(endPoint, getQuicSession().getExecutor(), getQuicSession().getByteBufferPool(), encoder, decoder, apiSession);
        endPoint.setConnection(connection);
        endPoint.onOpen();
        connection.onOpen();
    }

    void writeFrame(long streamId, Frame frame, Callback callback)
    {
        QuicStreamEndPoint endPoint = getOrCreateStreamEndPoint(streamId, this::configureProtocolEndPoint);
        messageFlusher.offer(endPoint, frame, callback);
        messageFlusher.iterate();
    }
}
