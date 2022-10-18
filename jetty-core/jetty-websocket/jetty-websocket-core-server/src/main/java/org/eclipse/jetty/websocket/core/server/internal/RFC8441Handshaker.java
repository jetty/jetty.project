//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.core.server.internal;

import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.RetainableByteBufferPool;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.TunnelSupport;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.internal.WebSocketConnection;
import org.eclipse.jetty.websocket.core.internal.WebSocketCoreSession;

public class RFC8441Handshaker extends AbstractHandshaker
{
    @Override
    public boolean isWebSocketUpgradeRequest(Request request)
    {
        if (!HttpMethod.CONNECT.is(request.getMethod()))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("not upgraded method!=GET {}", request);
            return false;
        }

        if (!HttpVersion.HTTP_2.is(request.getConnectionMetaData().getProtocol()))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("not upgraded HttpVersion!=2 {}", request);
            return false;
        }

        return super.isWebSocketUpgradeRequest(request);
    }

    @Override
    protected WebSocketNegotiation newNegotiation(Request request, Response response, Callback callback, WebSocketComponents webSocketComponents)
    {
        return new RFC8441Negotiation(request, response, callback, webSocketComponents);
    }

    @Override
    protected WebSocketConnection createWebSocketConnection(Request request, WebSocketCoreSession coreSession)
    {
        Connector connector = request.getConnectionMetaData().getConnector();
        ByteBufferPool byteBufferPool = connector.getByteBufferPool();
        RetainableByteBufferPool retainableByteBufferPool = byteBufferPool.asRetainableByteBufferPool();
        TunnelSupport tunnelSupport = request.getTunnelSupport();
        EndPoint endPoint = tunnelSupport.getEndPoint();
        return newWebSocketConnection(endPoint, connector.getExecutor(), connector.getScheduler(), byteBufferPool, retainableByteBufferPool, coreSession);
    }

    @Override
    protected void prepareResponse(Response response, WebSocketNegotiation negotiation)
    {
        response.setStatus(HttpStatus.OK_200);
    }
}
