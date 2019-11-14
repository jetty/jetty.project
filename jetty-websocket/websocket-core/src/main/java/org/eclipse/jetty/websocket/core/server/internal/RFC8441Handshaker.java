//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.core.server.internal;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.internal.WebSocketConnection;
import org.eclipse.jetty.websocket.core.internal.WebSocketCoreSession;
import org.eclipse.jetty.websocket.core.server.Negotiation;

public class RFC8441Handshaker extends AbstractHandshaker
{
    @Override
    protected boolean validateRequest(HttpServletRequest request)
    {
        if (!HttpMethod.CONNECT.is(request.getMethod()))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("not upgraded method!=GET {}", request);
            return false;
        }

        if (!HttpVersion.HTTP_2.is(request.getProtocol()))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("not upgraded HttpVersion!=2 {}", request);
            return false;
        }

        return true;
    }

    @Override
    protected Negotiation newNegotiation(HttpServletRequest request, HttpServletResponse response, WebSocketComponents webSocketComponents)
    {
        return new RFC8441Negotiation(Request.getBaseRequest(request), request, response, webSocketComponents);
    }

    @Override
    protected WebSocketConnection createWebSocketConnection(Request baseRequest, WebSocketCoreSession coreSession)
    {
        HttpChannel httpChannel = baseRequest.getHttpChannel();
        Connector connector = httpChannel.getConnector();
        EndPoint endPoint = httpChannel.getTunnellingEndPoint();
        return newWebSocketConnection(endPoint, connector.getExecutor(), connector.getScheduler(), connector.getByteBufferPool(), coreSession);
    }

    @Override
    protected void prepareResponse(Response response, Negotiation negotiation)
    {
        response.setStatus(HttpStatus.OK_200);
    }
}
