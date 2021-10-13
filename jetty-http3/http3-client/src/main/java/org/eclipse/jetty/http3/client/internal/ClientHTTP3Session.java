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

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jetty.http3.HTTP3Configuration;
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

    private final QpackEncoder encoder;
    private final QpackDecoder decoder;
    private final HTTP3SessionClient session;
    private final ControlFlusher controlFlusher;
    private final HTTP3Flusher messageFlusher;

    public ClientHTTP3Session(HTTP3Configuration configuration, ClientQuicSession quicSession, Session.Client.Listener listener, Promise<Session.Client> promise)
    {
        super(quicSession);
        this.session = new HTTP3SessionClient(this, listener, promise);
        addBean(session);

        if (LOG.isDebugEnabled())
            LOG.debug("initializing HTTP/3 streams");

        long encoderStreamId = getQuicSession().newStreamId(StreamType.CLIENT_UNIDIRECTIONAL);
        QuicStreamEndPoint encoderEndPoint = configureInstructionEndPoint(encoderStreamId);
        InstructionFlusher encoderInstructionFlusher = new InstructionFlusher(quicSession, encoderEndPoint, EncoderStreamConnection.STREAM_TYPE);
        this.encoder = new QpackEncoder(new InstructionHandler(encoderInstructionFlusher), configuration.getMaxBlockedStreams());
        addBean(encoder);
        if (LOG.isDebugEnabled())
            LOG.debug("created encoder stream #{} on {}", encoderStreamId, encoderEndPoint);

        long decoderStreamId = getQuicSession().newStreamId(StreamType.CLIENT_UNIDIRECTIONAL);
        QuicStreamEndPoint decoderEndPoint = configureInstructionEndPoint(decoderStreamId);
        InstructionFlusher decoderInstructionFlusher = new InstructionFlusher(quicSession, decoderEndPoint, DecoderStreamConnection.STREAM_TYPE);
        this.decoder = new QpackDecoder(new InstructionHandler(decoderInstructionFlusher), configuration.getMaxResponseHeadersSize());
        addBean(decoder);
        if (LOG.isDebugEnabled())
            LOG.debug("created decoder stream #{} on {}", decoderStreamId, decoderEndPoint);

        long controlStreamId = getQuicSession().newStreamId(StreamType.CLIENT_UNIDIRECTIONAL);
        QuicStreamEndPoint controlEndPoint = configureControlEndPoint(controlStreamId);
        this.controlFlusher = new ControlFlusher(quicSession, controlEndPoint, true);
        addBean(controlFlusher);
        if (LOG.isDebugEnabled())
            LOG.debug("created control stream #{} on {}", controlStreamId, controlEndPoint);

        this.messageFlusher = new HTTP3Flusher(quicSession.getByteBufferPool(), encoder, configuration.getMaxRequestHeadersSize(), configuration.isUseOutputDirectByteBuffers());
        addBean(messageFlusher);
    }

    public QpackDecoder getQpackDecoder()
    {
        return decoder;
    }

    public HTTP3SessionClient getSessionClient()
    {
        return session;
    }

    public long getStreamIdleTimeout()
    {
        return session.getStreamIdleTimeout();
    }

    public void setStreamIdleTimeout(long streamIdleTimeout)
    {
        session.setStreamIdleTimeout(streamIdleTimeout);
    }

    @Override
    protected void doStart() throws Exception
    {
        super.doStart();
        // Queue the mandatory SETTINGS frame.
        Map<Long, Long> settings = session.onPreface();
        if (settings == null)
            settings = Map.of();
        // TODO: add default settings.
        SettingsFrame frame = new SettingsFrame(settings);
        controlFlusher.offer(frame, Callback.from(Invocable.InvocationType.NON_BLOCKING, session::onOpen, this::fail));
        controlFlusher.iterate();
    }

    private void fail(Throwable failure)
    {
        // TODO: must close the connection.
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

    @Override
    protected boolean onIdleTimeout()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("idle timeout {} ms expired for {}", getQuicSession().getIdleTimeout(), this);
        return session.onIdleTimeout();
    }

    @Override
    public void inwardClose(long error, String reason)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("inward closing 0x{}/{} on {}", Long.toHexString(error), reason, this);
        session.disconnect(reason);
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

    private void configureUnidirectionalStreamEndPoint(QuicStreamEndPoint endPoint)
    {
        UnidirectionalStreamConnection connection = new UnidirectionalStreamConnection(endPoint, getQuicSession().getExecutor(), getQuicSession().getByteBufferPool(), encoder, decoder, session);
        endPoint.setConnection(connection);
        endPoint.onOpen();
        connection.onOpen();
    }

    void writeControlFrame(Frame frame, Callback callback)
    {
        controlFlusher.offer(frame, callback);
        controlFlusher.iterate();
    }

    void writeMessageFrame(long streamId, Frame frame, Callback callback)
    {
        QuicStreamEndPoint endPoint = getOrCreateStreamEndPoint(streamId, this::configureProtocolEndPoint);
        messageFlusher.offer(endPoint, frame, callback);
        messageFlusher.iterate();
    }

    public void onDataAvailable(long streamId)
    {
        session.onDataAvailable(streamId);
    }
}
