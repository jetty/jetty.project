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

package org.eclipse.jetty.http3.server.internal;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jetty.http3.HTTP3Configuration;
import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.frames.Frame;
import org.eclipse.jetty.http3.frames.SettingsFrame;
import org.eclipse.jetty.http3.internal.ControlFlusher;
import org.eclipse.jetty.http3.internal.DecoderStreamConnection;
import org.eclipse.jetty.http3.internal.EncoderStreamConnection;
import org.eclipse.jetty.http3.internal.Grease;
import org.eclipse.jetty.http3.internal.HTTP3ErrorCode;
import org.eclipse.jetty.http3.internal.InstructionFlusher;
import org.eclipse.jetty.http3.internal.InstructionHandler;
import org.eclipse.jetty.http3.internal.MessageFlusher;
import org.eclipse.jetty.http3.internal.UnidirectionalStreamConnection;
import org.eclipse.jetty.http3.qpack.QpackDecoder;
import org.eclipse.jetty.http3.qpack.QpackEncoder;
import org.eclipse.jetty.quic.common.QuicStreamEndPoint;
import org.eclipse.jetty.quic.common.StreamType;
import org.eclipse.jetty.quic.server.ServerProtocolSession;
import org.eclipse.jetty.quic.server.ServerQuicSession;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Invocable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServerHTTP3Session extends ServerProtocolSession
{
    private static final Logger LOG = LoggerFactory.getLogger(ServerHTTP3Session.class);

    private final HTTP3Configuration configuration;
    private final HTTP3SessionServer session;
    private final QpackEncoder encoder;
    private final QpackDecoder decoder;
    private final ControlFlusher controlFlusher;
    private final MessageFlusher messageFlusher;

    public ServerHTTP3Session(HTTP3Configuration configuration, ServerQuicSession quicSession, Session.Server.Listener listener)
    {
        super(quicSession);
        this.configuration = configuration;
        session = new HTTP3SessionServer(this, listener);
        addBean(session);
        session.setStreamIdleTimeout(configuration.getStreamIdleTimeout());

        if (LOG.isDebugEnabled())
            LOG.debug("initializing HTTP/3 streams");

        long encoderStreamId = newStreamId(StreamType.SERVER_UNIDIRECTIONAL);
        QuicStreamEndPoint encoderEndPoint = openInstructionEndPoint(encoderStreamId);
        InstructionFlusher encoderInstructionFlusher = new InstructionFlusher(quicSession, encoderEndPoint, EncoderStreamConnection.STREAM_TYPE);
        encoder = new QpackEncoder(new InstructionHandler(encoderInstructionFlusher));
        encoder.setMaxHeadersSize(configuration.getMaxResponseHeadersSize());
        addBean(encoder);
        if (LOG.isDebugEnabled())
            LOG.debug("created encoder stream #{} on {}", encoderStreamId, encoderEndPoint);

        long decoderStreamId = newStreamId(StreamType.SERVER_UNIDIRECTIONAL);
        QuicStreamEndPoint decoderEndPoint = openInstructionEndPoint(decoderStreamId);
        InstructionFlusher decoderInstructionFlusher = new InstructionFlusher(quicSession, decoderEndPoint, DecoderStreamConnection.STREAM_TYPE);
        decoder = new QpackDecoder(new InstructionHandler(decoderInstructionFlusher));
        addBean(decoder);
        if (LOG.isDebugEnabled())
            LOG.debug("created decoder stream #{} on {}", decoderStreamId, decoderEndPoint);

        long controlStreamId = newStreamId(StreamType.SERVER_UNIDIRECTIONAL);
        QuicStreamEndPoint controlEndPoint = openControlEndPoint(controlStreamId);
        controlFlusher = new ControlFlusher(quicSession, controlEndPoint, configuration.isUseOutputDirectByteBuffers());
        addBean(controlFlusher);
        if (LOG.isDebugEnabled())
            LOG.debug("created control stream #{} on {}", controlStreamId, controlEndPoint);

        messageFlusher = new MessageFlusher(quicSession.getByteBufferPool(), encoder, configuration.isUseOutputDirectByteBuffers());
        addBean(messageFlusher);
    }

    public QpackDecoder getQpackDecoder()
    {
        return decoder;
    }

    public QpackEncoder getQpackEncoder()
    {
        return encoder;
    }

    public HTTP3SessionServer getSessionServer()
    {
        return session;
    }

    public long newStreamId(StreamType streamType)
    {
        return getQuicSession().newStreamId(streamType);
    }

    @Override
    protected void onStart()
    {
        Map<Long, Long> settings = session.onPreface();
        settings = settings != null ? new HashMap<>(settings) : new HashMap<>();

        settings.compute(SettingsFrame.MAX_TABLE_CAPACITY, (k, v) ->
        {
            if (v == null)
            {
                v = (long)configuration.getMaxDecoderTableCapacity();
                if (v == 0)
                    v = null;
            }
            return v;
        });
        settings.compute(SettingsFrame.MAX_FIELD_SECTION_SIZE, (k, v) ->
        {
            if (v == null)
            {
                v = (long)configuration.getMaxRequestHeadersSize();
                if (v <= 0)
                    v = null;
            }
            return v;
        });
        settings.compute(SettingsFrame.MAX_BLOCKED_STREAMS, (k, v) ->
        {
            if (v == null)
            {
                v = (long)configuration.getMaxBlockedStreams();
                if (v == 0)
                    v = null;
            }
            return v;
        });

        if (LOG.isDebugEnabled())
            LOG.debug("configuring decoder {} on {}", settings, this);

        settings.forEach((key, value) ->
        {
            if (key == SettingsFrame.MAX_TABLE_CAPACITY)
                decoder.setMaxTableCapacity(value.intValue());
            else if (key == SettingsFrame.MAX_FIELD_SECTION_SIZE)
                decoder.setMaxHeadersSize(value.intValue());
            else if (key == SettingsFrame.MAX_BLOCKED_STREAMS)
                decoder.setMaxBlockedStreams(value.intValue());
        });

        // Queue the mandatory SETTINGS frame.
        SettingsFrame frame = new SettingsFrame(settings);
        if (controlFlusher.offer(frame, Callback.from(Invocable.InvocationType.NON_BLOCKING, session::onOpen, this::failControlStream)))
            controlFlusher.iterate();
    }

    public void onSettings(SettingsFrame frame)
    {
        Map<Long, Long> settings = frame.getSettings();
        if (LOG.isDebugEnabled())
            LOG.debug("configuring encoder {} on {}", settings, this);
        settings.forEach((key, value) ->
        {
            if (key == SettingsFrame.MAX_TABLE_CAPACITY)
            {
                int maxTableCapacity = (int)Math.min(value, Integer.MAX_VALUE);
                encoder.setMaxTableCapacity(maxTableCapacity);
                encoder.setTableCapacity(Math.min(maxTableCapacity, configuration.getMaxEncoderTableCapacity()));
            }
            else if (key == SettingsFrame.MAX_FIELD_SECTION_SIZE)
            {
                // Must cap the maxHeaderSize to avoid large allocations.
                int maxHeadersSize = (int)Math.min(value, configuration.getMaxResponseHeadersSize());
                encoder.setMaxHeadersSize(maxHeadersSize);
            }
            else if (key == SettingsFrame.MAX_BLOCKED_STREAMS)
            {
                int maxBlockedStreams = (int)Math.min(value, Integer.MAX_VALUE);
                encoder.setMaxBlockedStreams(maxBlockedStreams);
            }
            else
            {
                // SPEC: grease and unknown settings are ignored.
                if (LOG.isDebugEnabled())
                    LOG.debug("ignored {} setting {}={}", Grease.isGreaseValue(key) ? "grease" : "unknown", key, value);
            }
        });
    }

    private void failControlStream(Throwable failure)
    {
        long error = HTTP3ErrorCode.CLOSED_CRITICAL_STREAM_ERROR.code();
        onFailure(error, "control_stream_failure", failure);
    }

    private QuicStreamEndPoint openInstructionEndPoint(long streamId)
    {
        // This is a write-only stream, so no need to link a Connection.
        return getOrCreateStreamEndPoint(streamId, QuicStreamEndPoint::opened);
    }

    private QuicStreamEndPoint openControlEndPoint(long streamId)
    {
        // This is a write-only stream, so no need to link a Connection.
        return getOrCreateStreamEndPoint(streamId, QuicStreamEndPoint::opened);
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
            QuicStreamEndPoint streamEndPoint = getOrCreateStreamEndPoint(readableStreamId, this::openUnidirectionalStreamEndPoint);
            if (LOG.isDebugEnabled())
                LOG.debug("unidirectional stream #{} selected for read: {}", readableStreamId, streamEndPoint);
            return streamEndPoint.onReadable();
        }
    }

    @Override
    protected boolean onIdleTimeout()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("idle timeout {} ms expired for {}", getQuicSession().getIdleTimeout(), this);
        return session.onIdleTimeout();
    }

    @Override
    protected void onFailure(long error, String reason, Throwable failure)
    {
        session.onSessionFailure(HTTP3ErrorCode.NO_ERROR.code(), "failure", failure);
    }

    @Override
    public void inwardClose(long error, String reason)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("inward closing 0x{}/{} on {}", Long.toHexString(error), reason, this);
        session.inwardClose(error, reason);
    }

    @Override
    public CompletableFuture<Void> shutdown()
    {
        return session.shutdown();
    }

    @Override
    protected void onClose(long error, String reason)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("session closed remotely 0x{}/{} {}", Long.toHexString(error), reason, this);
        session.onClose(error, reason);
    }

    private void openUnidirectionalStreamEndPoint(QuicStreamEndPoint endPoint)
    {
        UnidirectionalStreamConnection connection = new UnidirectionalStreamConnection(endPoint, getQuicSession().getExecutor(), getQuicSession().getByteBufferPool(), encoder, decoder, session);
        endPoint.setConnection(connection);
        endPoint.opened();
    }

    void writeControlFrame(Frame frame, Callback callback)
    {
        if (controlFlusher.offer(frame, callback))
            controlFlusher.iterate();
    }

    void writeMessageFrame(long streamId, Frame frame, Callback callback)
    {
        QuicStreamEndPoint endPoint = getOrCreateStreamEndPoint(streamId, this::openProtocolEndPoint);
        if (messageFlusher.offer(endPoint, frame, callback))
            messageFlusher.iterate();
    }

    public void onDataAvailable(long streamId)
    {
        session.onDataAvailable(streamId);
    }
}
