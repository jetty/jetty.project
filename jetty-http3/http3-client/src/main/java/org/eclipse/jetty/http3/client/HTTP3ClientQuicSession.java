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

package org.eclipse.jetty.http3.client;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.frames.Frame;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.frames.SettingsFrame;
import org.eclipse.jetty.http3.internal.ControlConnection;
import org.eclipse.jetty.http3.internal.ControlFlusher;
import org.eclipse.jetty.http3.internal.DecoderConnection;
import org.eclipse.jetty.http3.internal.EncoderConnection;
import org.eclipse.jetty.http3.internal.VarLenInt;
import org.eclipse.jetty.http3.internal.generator.Generator;
import org.eclipse.jetty.quic.client.ClientQuicSession;
import org.eclipse.jetty.quic.client.ProtocolClientQuicSession;
import org.eclipse.jetty.quic.common.QuicStreamEndPoint;
import org.eclipse.jetty.util.Callback;

public class HTTP3ClientQuicSession extends ProtocolClientQuicSession implements Session
{
    private final Session.Listener listener;
    private final QuicStreamEndPoint decoderEndPoint;
    private final QuicStreamEndPoint encoderEndPoint;
    private final QuicStreamEndPoint controlEndPoint;
    private final ControlFlusher controlFlusher;

    public HTTP3ClientQuicSession(ClientQuicSession session, Session.Listener listener, Generator generator)
    {
        super(session);
        this.listener = listener;

        long decoderStreamId = getQuicSession().newClientUnidirectionalStreamId();
        decoderEndPoint = configureDecoderEndPoint(decoderStreamId);

        long encoderStreamId = getQuicSession().newClientUnidirectionalStreamId();
        encoderEndPoint = configureEncoderEndPoint(encoderStreamId);

        long controlStreamId = getQuicSession().newClientBidirectionalStreamId();
        this.controlEndPoint = configureControlEndPoint(controlStreamId);
        this.controlFlusher = new ControlFlusher(session, generator, controlEndPoint);
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
            // This is a write-only stream, so no need to link a Connection.
            endPoint.onOpen();

            int streamType = DecoderConnection.QPACK_DECODER_STREAM_TYPE;
            ByteBuffer buffer = ByteBuffer.allocate(VarLenInt.length(streamType));
            VarLenInt.generate(buffer, streamType);
            buffer.flip();
            endPoint.write(Callback.NOOP, buffer);
        });
    }

    private QuicStreamEndPoint configureEncoderEndPoint(long streamId)
    {
        return getOrCreateStreamEndPoint(streamId, endPoint ->
        {
            // This is a write-only stream, so no need to link a Connection.
            endPoint.onOpen();

            int streamType = EncoderConnection.QPACK_ENCODER_STREAM_TYPE;
            ByteBuffer buffer = ByteBuffer.allocate(VarLenInt.length(streamType));
            VarLenInt.generate(buffer, streamType);
            buffer.flip();
            endPoint.write(Callback.NOOP, buffer);
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

    @Override
    public CompletableFuture<Stream> newStream(HeadersFrame frame, Stream.Listener listener)
    {
        return null;
    }
}
