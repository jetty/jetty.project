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

package org.eclipse.jetty.http3.client.internal;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.http3.ControlFlusher;
import org.eclipse.jetty.http3.Grease;
import org.eclipse.jetty.http3.HTTP3Configuration;
import org.eclipse.jetty.http3.HTTP3ErrorCode;
import org.eclipse.jetty.http3.InstructionFlusher;
import org.eclipse.jetty.http3.InstructionHandler;
import org.eclipse.jetty.http3.MessageFlusher;
import org.eclipse.jetty.http3.StreamType;
import org.eclipse.jetty.http3.UnidirectionalStreamConnection;
import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.client.HTTP3Client;
import org.eclipse.jetty.http3.client.HTTP3SessionClient;
import org.eclipse.jetty.http3.frames.Frame;
import org.eclipse.jetty.http3.frames.SettingsFrame;
import org.eclipse.jetty.http3.qpack.QpackDecoder;
import org.eclipse.jetty.http3.qpack.QpackEncoder;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.quic.api.Stream;
import org.eclipse.jetty.quic.api.frames.ConnectionCloseFrame;
import org.eclipse.jetty.quic.client.ClientProtocolSession;
import org.eclipse.jetty.quic.common.ProtocolSession;
import org.eclipse.jetty.quic.common.StreamEndPoint;
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
    private final InstructionFlusher encoderFlusher;
    private final QpackEncoder encoder;
    private final InstructionFlusher decoderFlusher;
    private final QpackDecoder decoder;
    private final ControlFlusher controlFlusher;
    private final MessageFlusher messageFlusher;

    public ClientHTTP3Session(ClientConnector connector, HTTP3Configuration configuration, org.eclipse.jetty.quic.api.Session quicSession, ClientConnectionFactory connectionFactory, Map<String, Object> context)
    {
        super(connector, quicSession, connectionFactory, context);
        this.configuration = configuration;
        Session.Client.Listener listener = (Session.Client.Listener)context.get(HTTP3Client.SESSION_LISTENER_CONTEXT_KEY);
        @SuppressWarnings("unchecked")
        Promise.Invocable<Session.Client> promise = (Promise.Invocable<Session.Client>)context.get(HTTP3Client.SESSION_PROMISE_CONTEXT_KEY);
        session = new HTTP3SessionClient(connector.getScheduler(), this, listener, promise);
        installBean(session);
        session.setStreamIdleTimeout(configuration.getStreamIdleTimeout());

        if (LOG.isDebugEnabled())
            LOG.debug("initializing HTTP/3 streams");

        long encoderStreamId = quicSession.newStreamId(false);
        StreamEndPoint encoderEndPoint = openInstructionEndPoint(encoderStreamId);
        encoderFlusher = new InstructionFlusher(getByteBufferPool(), encoderEndPoint, StreamType.ENCODER_STREAM);
        installBean(encoderFlusher);
        encoder = new QpackEncoder(new InstructionHandler(encoderFlusher));
        encoder.setMaxHeadersSize(configuration.getMaxRequestHeadersSize());
        installBean(encoder);
        if (LOG.isDebugEnabled())
            LOG.debug("created encoder stream #{} on {}", encoderStreamId, encoderEndPoint);

        long decoderStreamId = quicSession.newStreamId(false);
        StreamEndPoint decoderEndPoint = openInstructionEndPoint(decoderStreamId);
        decoderFlusher = new InstructionFlusher(getByteBufferPool(), decoderEndPoint, StreamType.DECODER_STREAM);
        installBean(decoderFlusher);
        decoder = new QpackDecoder(new InstructionHandler(decoderFlusher));
        installBean(decoder);
        if (LOG.isDebugEnabled())
            LOG.debug("created decoder stream #{} on {}", decoderStreamId, decoderEndPoint);

        long controlStreamId = quicSession.newStreamId(false);
        StreamEndPoint controlEndPoint = openControlEndPoint(controlStreamId);
        controlFlusher = new ControlFlusher(getByteBufferPool(), controlEndPoint, configuration.isUseOutputDirectByteBuffers());
        installBean(controlFlusher);
        if (LOG.isDebugEnabled())
            LOG.debug("created control stream #{} on {}", controlStreamId, controlEndPoint);

        messageFlusher = new MessageFlusher(getByteBufferPool(), encoder, configuration.isUseOutputDirectByteBuffers());
        installBean(messageFlusher);
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

    @Override
    protected void onStop()
    {
        messageFlusher.close();
        controlFlusher.close();
        decoderFlusher.close();
        encoderFlusher.close();
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
                int maxHeadersSize = (int)Math.min(value, configuration.getMaxRequestHeadersSize());
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

    @Override
    protected Connection newConnection(StreamEndPoint endPoint) throws IOException
    {
        if (endPoint.getStream().isBidirectional())
            return super.newConnection(endPoint);
        return new UnidirectionalStreamConnection(endPoint, getExecutor(), getByteBufferPool(), getQpackEncoder(), getQpackDecoder(), session.getParserListener());
    }

    @Override
    public void close(ConnectionCloseFrame frame, Promise.Invocable<ProtocolSession> promise)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("session closed locally {} {}", frame, this);
        // Propagate the close inwards.
        session.close(frame.errorCode(), frame.reason(), Promise.Invocable.toPromise(promise, s -> this));
    }

    @Override
    public void onClose(ConnectionCloseFrame frame)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("session closed remotely {} {}", frame, this);
        // Propagate the close inwards.
        session.onClose(frame.errorCode(), frame.reason());
    }

    private StreamEndPoint openInstructionEndPoint(long streamId)
    {
        // TODO: add a Stream.Listener for failure/close events.
        // This is a write-only stream, so no need to link a Connection.
        Stream stream = getSession().newStream(streamId, null);
        return createStreamEndPoint(stream, StreamEndPoint::onOpen);
    }

    private StreamEndPoint openControlEndPoint(long streamId)
    {
        // TODO: add a Stream.Listener for failure/close events.
        // This is a write-only stream, so no need to link a Connection.
        Stream stream = getSession().newStream(streamId, null);
        return createStreamEndPoint(stream, StreamEndPoint::onOpen);
    }

    @Override
    public boolean onIdleTimeout(TimeoutException timeout)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("idle timeout {} ms expired for {}", getSession().getIdleTimeout(), this);
        return session.onIdleTimeout(timeout);
    }

    @Override
    public void onStreamFailure(long streamId, Throwable failure)
    {
        session.onStreamFailure(streamId, HTTP3ErrorCode.REQUEST_CANCELLED_ERROR.code(), failure);
    }

    private void onFailure(long error, String reason, Throwable failure)
    {
        session.onSessionFailure(error, reason, failure);
    }

    @Override
    public CompletableFuture<ProtocolSession> shutdown()
    {
        // Propagate inwards.
        return session.shutdown().thenApply(s -> this);
    }

    public void writeControlFrame(Frame frame, Callback callback)
    {
        if (controlFlusher.offer(frame, callback))
            controlFlusher.iterate();
    }

    public void writeMessageFrame(StreamEndPoint streamEndPoint, Frame frame, Callback callback)
    {
        if (messageFlusher.offer(streamEndPoint, frame, callback))
            messageFlusher.iterate();
    }
}
