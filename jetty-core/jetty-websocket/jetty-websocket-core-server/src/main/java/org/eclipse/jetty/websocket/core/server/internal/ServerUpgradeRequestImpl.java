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

package org.eclipse.jetty.websocket.core.server.internal;

import java.util.List;

import org.eclipse.jetty.http.HttpException;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.websocket.core.ExtensionConfig;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.WebSocketConstants;
import org.eclipse.jetty.websocket.core.server.ServerUpgradeRequest;

/**
 * Internal implementation of the {@link ServerUpgradeRequest} interface used during the WebSocket negotiation on the server side.
 * @see ServerUpgradeRequest
 */
public class ServerUpgradeRequestImpl extends Request.Wrapper implements ServerUpgradeRequest
{
    private final Request request;
    private final WebSocketNegotiation negotiation;

    public ServerUpgradeRequestImpl(WebSocketNegotiation negotiation, Request baseRequest) throws HttpException.RuntimeException
    {
        super(baseRequest);
        this.negotiation = negotiation;
        this.request = baseRequest;
    }

    @Override
    public WebSocketComponents getWebSocketComponents()
    {
        return negotiation.getWebSocketComponents();
    }

    @Override
    public List<ExtensionConfig> getExtensions()
    {
        return negotiation.getOfferedExtensions();
    }

    @Override
    public String getProtocolVersion()
    {
        String version = request.getHeaders().get(HttpHeader.SEC_WEBSOCKET_VERSION.asString());
        if (version == null)
        {
            return Integer.toString(WebSocketConstants.SPEC_VERSION);
        }
        return version;
    }

    @Override
    public List<String> getSubProtocols()
    {
        return negotiation.getOfferedSubprotocols();
    }

    @Override
    public boolean hasSubProtocol(String subprotocol)
    {
        for (String protocol : getSubProtocols())
        {
            if (protocol.equalsIgnoreCase(subprotocol))
                return true;
        }
        return false;
    }
}
