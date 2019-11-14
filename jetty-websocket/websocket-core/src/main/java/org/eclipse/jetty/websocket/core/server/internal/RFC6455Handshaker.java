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

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.internal.WebSocketConnection;
import org.eclipse.jetty.websocket.core.internal.WebSocketCore;
import org.eclipse.jetty.websocket.core.internal.WebSocketCoreSession;
import org.eclipse.jetty.websocket.core.server.Negotiation;

public final class RFC6455Handshaker extends AbstractHandshaker
{
    private static final HttpField UPGRADE_WEBSOCKET = new PreEncodedHttpField(HttpHeader.UPGRADE, "WebSocket");
    private static final HttpField CONNECTION_UPGRADE = new PreEncodedHttpField(HttpHeader.CONNECTION, HttpHeader.UPGRADE.asString());

    @Override
    protected boolean validateRequest(HttpServletRequest request)
    {
        if (!HttpMethod.GET.is(request.getMethod()))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("not upgraded method!=GET {}", request);
            return false;
        }

        if (!HttpVersion.HTTP_1_1.is(request.getProtocol()))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("not upgraded version!=1.1 {}", request);
            return false;
        }

        return true;
    }

    @Override
    protected Negotiation newNegotiation(HttpServletRequest request, HttpServletResponse response, WebSocketComponents webSocketComponents)
    {
        return new RFC6544Negotiation(Request.getBaseRequest(request), request, response, webSocketComponents);
    }

    @Override
    protected boolean validateNegotiation(Negotiation negotiation)
    {
        boolean result = super.validateNegotiation(negotiation);
        if (!result)
            return false;
        if (((RFC6544Negotiation)negotiation).getKey() == null)
            throw new BadMessageException("Missing request header 'Sec-WebSocket-Key'");
        return true;
    }

    @Override
    protected WebSocketConnection createWebSocketConnection(Request baseRequest, WebSocketCoreSession coreSession)
    {
        HttpChannel httpChannel = baseRequest.getHttpChannel();
        Connector connector = httpChannel.getConnector();
        return newWebSocketConnection(httpChannel.getEndPoint(), connector.getExecutor(), connector.getScheduler(), connector.getByteBufferPool(), coreSession);
    }

    @Override
    protected void prepareResponse(Response response, Negotiation negotiation)
    {
        response.setStatus(HttpServletResponse.SC_SWITCHING_PROTOCOLS);
        HttpFields responseFields = response.getHttpFields();
        responseFields.put(UPGRADE_WEBSOCKET);
        responseFields.put(CONNECTION_UPGRADE);
        responseFields.put(HttpHeader.SEC_WEBSOCKET_ACCEPT, WebSocketCore.hashKey(((RFC6544Negotiation)negotiation).getKey()));
    }
}
