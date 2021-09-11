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

package org.eclipse.jetty.http3.server;

import java.util.Map;
import java.util.Objects;

import org.eclipse.jetty.http3.api.server.ServerSessionListener;
import org.eclipse.jetty.http3.internal.HTTP3Connection;
import org.eclipse.jetty.http3.internal.HTTP3Session;
import org.eclipse.jetty.http3.internal.generator.Generator;
import org.eclipse.jetty.http3.internal.parser.Parser;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.quic.common.ProtocolQuicSession;
import org.eclipse.jetty.quic.common.QuicSession;
import org.eclipse.jetty.quic.common.QuicStreamEndPoint;
import org.eclipse.jetty.quic.server.ServerQuicSession;
import org.eclipse.jetty.server.AbstractConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.util.annotation.ManagedAttribute;

public abstract class AbstractHTTP3ServerConnectionFactory extends AbstractConnectionFactory implements ProtocolQuicSession.Factory
{
    private final HttpConfiguration httpConfiguration;
    private final ServerSessionListener listener;
    private boolean useInputDirectByteBuffers = true;
    private boolean useOutputDirectByteBuffers = true;

    public AbstractHTTP3ServerConnectionFactory(HttpConfiguration httpConfiguration, ServerSessionListener listener)
    {
        super("h3");
        this.httpConfiguration = Objects.requireNonNull(httpConfiguration);
        addBean(httpConfiguration);
        this.listener = listener;
    }

    @ManagedAttribute("Whether to use direct ByteBuffers for reading")
    public boolean isUseInputDirectByteBuffers()
    {
        return useInputDirectByteBuffers;
    }

    public void setUseInputDirectByteBuffers(boolean useInputDirectByteBuffers)
    {
        this.useInputDirectByteBuffers = useInputDirectByteBuffers;
    }

    @ManagedAttribute("Whether to use direct ByteBuffers for writing")
    public boolean isUseOutputDirectByteBuffers()
    {
        return useOutputDirectByteBuffers;
    }

    public void setUseOutputDirectByteBuffers(boolean useOutputDirectByteBuffers)
    {
        this.useOutputDirectByteBuffers = useOutputDirectByteBuffers;
    }

    public HttpConfiguration getHttpConfiguration()
    {
        return httpConfiguration;
    }

    @Override
    public ProtocolQuicSession newProtocolQuicSession(QuicSession quicSession, Map<String, Object> context)
    {
        Generator generator = new Generator();
        return new HTTP3ServerQuicSession((ServerQuicSession)quicSession, listener, generator);
    }

    @Override
    public Connection newConnection(Connector connector, EndPoint endPoint)
    {
        // TODO: can the downcasts be removed?
        QuicStreamEndPoint streamEndPoint = (QuicStreamEndPoint)endPoint;
        long streamId = streamEndPoint.getStreamId();
        HTTP3ServerQuicSession http3QuicSession = (HTTP3ServerQuicSession)streamEndPoint.getQuicSession().getProtocolQuicSession();

        // TODO: this is wrong, as the endpoint here is already per-stream
        //  Could it be that HTTP3[Client|Server]QuicSession and HTTP3Session are the same thing?
        //  If an app wants to send a SETTINGS frame, it calls Session.settings() and this has to go back to an object that knows the control stream,
        //  which is indeed HTTP3[Client|Server]QuicSession!
        HTTP3Session session = new HTTP3Session();

        Parser parser = new Parser(streamId, http3QuicSession.getQpackDecoder(), session);

        HTTP3Connection connection = new HTTP3Connection(endPoint, connector.getExecutor(), parser);
        return connection;
    }
}
