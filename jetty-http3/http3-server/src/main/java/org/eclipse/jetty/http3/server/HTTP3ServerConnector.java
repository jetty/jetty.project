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

import java.util.concurrent.Executor;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.quic.server.QuicServerConnector;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A HTTP/3 specific {@link QuicServerConnector} that configures QUIC parameters according to HTTP/3 requirements.</p>
 *
 * <p>HTTP/3+QUIC support is experimental and not suited for production use.
 * APIs may change incompatibly between releases.</p>
 */
public class HTTP3ServerConnector extends QuicServerConnector
{
    private static final Logger LOG = LoggerFactory.getLogger(HTTP3ServerConnector.class);

    private HttpField altSvcHttpField;

    public HTTP3ServerConnector(Server server, SslContextFactory.Server sslContextFactory, ConnectionFactory... factories)
    {
        this(server, null, null, null, sslContextFactory, factories);
    }

    public HTTP3ServerConnector(Server server, Executor executor, Scheduler scheduler, ByteBufferPool bufferPool, SslContextFactory.Server sslContextFactory, ConnectionFactory... factories)
    {
        super(server, executor, scheduler, bufferPool, sslContextFactory, factories);
        // Max concurrent streams that a client can open.
        getQuicConfiguration().setMaxBidirectionalRemoteStreams(128);
        // HTTP/3 requires a few mandatory unidirectional streams.
        getQuicConfiguration().setMaxUnidirectionalRemoteStreams(8);
        getQuicConfiguration().setUnidirectionalStreamRecvWindow(1024 * 1024);
    }

    @Override
    protected void doStart() throws Exception
    {
        LOG.info("HTTP/3+QUIC support is experimental and not suited for production use.");
        super.doStart();
        altSvcHttpField = new PreEncodedHttpField(HttpHeader.ALT_SVC, String.format("h3=\":%d\"", getLocalPort()));
    }

    public HttpField getAltSvcHttpField()
    {
        return altSvcHttpField;
    }
}
