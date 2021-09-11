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

import org.eclipse.jetty.http3.internal.ControlConnection;
import org.eclipse.jetty.http3.internal.DecoderConnection;
import org.eclipse.jetty.http3.internal.EncoderConnection;
import org.eclipse.jetty.quic.client.ClientQuicSession;
import org.eclipse.jetty.quic.client.ProtocolClientQuicSession;
import org.eclipse.jetty.quic.common.QuicStreamEndPoint;

public class HTTP3ClientQuicSession extends ProtocolClientQuicSession
{
    private QuicStreamEndPoint decoderEndPoint;
    private QuicStreamEndPoint encoderEndPoint;
    private QuicStreamEndPoint controlEndPoint;

    public HTTP3ClientQuicSession(ClientQuicSession session)
    {
        super(session);
    }

    @Override
    public void onOpen()
    {
        long decoderStreamId = getQuicSession().newClientUnidirectionalStreamId();
        decoderEndPoint = configureDecoderEndPoint(decoderStreamId);

        long encoderStreamId = getQuicSession().newClientUnidirectionalStreamId();
        encoderEndPoint = configureEncoderEndPoint(encoderStreamId);

        long controlStreamId = getQuicSession().newClientBidirectionalStreamId();
        controlEndPoint = configureControlEndPoint(controlStreamId);
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
}
