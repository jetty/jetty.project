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
import org.eclipse.jetty.http3.internal.DecoderStreamConnection;
import org.eclipse.jetty.http3.internal.EncoderStreamConnection;
import org.eclipse.jetty.http3.internal.HTTP3Flusher;
import org.eclipse.jetty.http3.internal.InstructionFlusher;
import org.eclipse.jetty.http3.internal.InstructionHandler;
import org.eclipse.jetty.http3.internal.UnidirectionalStreamConnection;
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
    private final HTTP3SessionServer applicationSession;
    private final ControlFlusher controlFlusher;
    private final HTTP3Flusher messageFlusher;

    public ServerHTTP3Session(ServerQuicSession session, Session.Server.Listener listener, int maxBlockedStreams, int maxRequestHeadersSize)
    {
        super(session);
        this.applicationSession = new HTTP3SessionServer(this, listener);

        if (LOG.isDebugEnabled())
            LOG.debug("initializing HTTP/3 streams");

        long encoderStreamId = getQuicSession().newStreamId(StreamType.SERVER_UNIDIRECTIONAL);
        QuicStreamEndPoint encoderEndPoint = configureInstructionEndPoint(encoderStreamId);
        InstructionFlusher encoderInstructionFlusher = new InstructionFlusher(session, encoderEndPoint, EncoderStreamConnection.STREAM_TYPE);
        this.encoder = new QpackEncoder(new InstructionHandler(encoderInstructionFlusher), maxBlockedStreams);
        if (LOG.isDebugEnabled())
            LOG.debug("created encoder stream #{} on {}", encoderStreamId, encoderEndPoint);

        long decoderStreamId = getQuicSession().newStreamId(StreamType.SERVER_UNIDIRECTIONAL);
        QuicStreamEndPoint decoderEndPoint = configureInstructionEndPoint(decoderStreamId);
        InstructionFlusher decoderInstructionFlusher = new InstructionFlusher(session, decoderEndPoint, DecoderStreamConnection.STREAM_TYPE);
        this.decoder = new QpackDecoder(new InstructionHandler(decoderInstructionFlusher), maxRequestHeadersSize);
        if (LOG.isDebugEnabled())
            LOG.debug("created decoder stream #{} on {}", decoderStreamId, decoderEndPoint);

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
        return applicationSession;
    }

    @Override
    public void onOpen()
    {
        // Queue the mandatory SETTINGS frame.
        Map<Long, Long> settings = applicationSession.onPreface();
        if (settings == null)
            settings = Map.of();
        // TODO: add default settings.
        SettingsFrame frame = new SettingsFrame(settings);
        controlFlusher.offer(frame, Callback.NOOP);
        controlFlusher.iterate();

        applicationSession.onOpen();
    }

    private QuicStreamEndPoint configureInstructionEndPoint(long streamId)
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
    protected boolean onReadable(long readableStreamId)
    {
        StreamType streamType = StreamType.from(readableStreamId);
        if (streamType == StreamType.CLIENT_BIDIRECTIONAL)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("bidirectional stream #{} selected for read", readableStreamId);
            return super.onReadable(readableStreamId);
        }
        else
        {
            QuicStreamEndPoint streamEndPoint = getOrCreateStreamEndPoint(readableStreamId, this::configureUnidirectionalStreamEndPoint);
            if (LOG.isDebugEnabled())
                LOG.debug("unidirectional stream #{} selected for read: {}", readableStreamId, streamEndPoint);
            return streamEndPoint.onReadable();
        }
    }

    private void configureUnidirectionalStreamEndPoint(QuicStreamEndPoint endPoint)
    {
        UnidirectionalStreamConnection connection = new UnidirectionalStreamConnection(endPoint, getQuicSession().getExecutor(), getQuicSession().getByteBufferPool(), encoder, decoder, applicationSession);
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

    protected void onDataAvailable(long streamId)
    {
        applicationSession.onDataAvailable(streamId);
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x", getClass().getSimpleName(), hashCode());
    }
}
