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

package org.eclipse.jetty.http3.client;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jetty.http3.ControlFlusher;
import org.eclipse.jetty.http3.DecoderStreamConnection;
import org.eclipse.jetty.http3.EncoderStreamConnection;
import org.eclipse.jetty.http3.HTTP3Configuration;
import org.eclipse.jetty.http3.HTTP3ErrorCode;
import org.eclipse.jetty.http3.InstructionFlusher;
import org.eclipse.jetty.http3.InstructionHandler;
import org.eclipse.jetty.http3.MessageFlusher;
import org.eclipse.jetty.http3.UnidirectionalStreamConnection;
import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.frames.Frame;
import org.eclipse.jetty.http3.frames.SettingsFrame;
import org.eclipse.jetty.http3.qpack.QpackDecoder;
import org.eclipse.jetty.http3.qpack.QpackEncoder;
import org.eclipse.jetty.quic.client.ClientProtocolSession;
import org.eclipse.jetty.quic.client.ClientQuicSession;
import org.eclipse.jetty.quic.common.QuicStreamEndPoint;
import org.eclipse.jetty.quic.common.StreamType;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.thread.Invocable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClientHTTP3Session extends ClientProtocolSession
{
    private static final Logger LOG = LoggerFactory.getLogger(ClientHTTP3Session.class);

    private final HTTP3Configuration configuration;
    private final HTTP3SessionClient session;
    private final QpackEncoder encoder;
    private final QpackDecoder decoder;
    private final ControlFlusher controlFlusher;
    private final MessageFlusher messageFlusher;

    public ClientHTTP3Session(HTTP3Configuration configuration, ClientQuicSession quicSession, Session.Client.Listener listener, Promise<Session.Client> promise)
    {
        super(quicSession);
        this.configuration = configuration;
        session = new HTTP3SessionClient(this, listener, promise);
        addBean(session);
        session.setStreamIdleTimeout(configuration.getStreamIdleTimeout());

        if (LOG.isDebugEnabled())
            LOG.debug("initializing HTTP/3 streams");

        long encoderStreamId = getQuicSession().newStreamId(StreamType.CLIENT_UNIDIRECTIONAL);
        QuicStreamEndPoint encoderEndPoint = openInstructionEndPoint(encoderStreamId);
        InstructionFlusher encoderInstructionFlusher = new InstructionFlusher(quicSession, encoderEndPoint, EncoderStreamConnection.STREAM_TYPE);
        encoder = new QpackEncoder(new InstructionHandler(encoderInstructionFlusher));
        encoder.setMaxHeadersSize(configuration.getMaxRequestHeadersSize());
        addBean(encoder);
        if (LOG.isDebugEnabled())
            LOG.debug("created encoder stream #{} on {}", encoderStreamId, encoderEndPoint);

        long decoderStreamId = getQuicSession().newStreamId(StreamType.CLIENT_UNIDIRECTIONAL);
        QuicStreamEndPoint decoderEndPoint = openInstructionEndPoint(decoderStreamId);
        InstructionFlusher decoderInstructionFlusher = new InstructionFlusher(quicSession, decoderEndPoint, DecoderStreamConnection.STREAM_TYPE);
        decoder = new QpackDecoder(new InstructionHandler(decoderInstructionFlusher));
        addBean(decoder);
        if (LOG.isDebugEnabled())
            LOG.debug("created decoder stream #{} on {}", decoderStreamId, decoderEndPoint);

        long controlStreamId = getQuicSession().newStreamId(StreamType.CLIENT_UNIDIRECTIONAL);
        QuicStreamEndPoint controlEndPoint = openControlEndPoint(controlStreamId);
        controlFlusher = new ControlFlusher(quicSession, controlEndPoint, true);
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

    public HTTP3SessionClient getSessionClient()
    {
        return session;
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
                v = (long)configuration.getMaxResponseHeadersSize();
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
            LOG.debug("configuring local {} on {}", settings, this);

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
                int maxTableCapacity = value.intValue();
                encoder.setMaxTableCapacity(maxTableCapacity);
                encoder.setTableCapacity(Math.min(maxTableCapacity, configuration.getInitialEncoderTableCapacity()));
            }
            else if (key == SettingsFrame.MAX_FIELD_SECTION_SIZE)
            {
                // Must cap the maxHeaderSize to avoid large allocations.
                int maxHeadersSize = Math.min(value.intValue(), configuration.getMaxRequestHeadersSize());
                encoder.setMaxHeadersSize(maxHeadersSize);
            }
            else if (key == SettingsFrame.MAX_BLOCKED_STREAMS)
            {
                int maxBlockedStreams = value.intValue();
                encoder.setMaxBlockedStreams(maxBlockedStreams);
            }
        });
    }

    private void failControlStream(Throwable failure)
    {
        long error = HTTP3ErrorCode.CLOSED_CRITICAL_STREAM_ERROR.code();
        onFailure(error, "control_stream_failure", failure);
    }

    @Override
    protected void onStop()
    {
        // Nothing to do, not even calling super,
        // as onStart() does not call super either.
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
        session.onSessionFailure(error, reason, failure);
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
}
