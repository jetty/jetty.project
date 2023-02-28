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

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.websocket.core.ExtensionConfig;
import org.eclipse.jetty.websocket.core.server.ServerUpgradeResponse;

/**
 * Upgrade response used for websocket negotiation.
 * Allows setting of extensions and subprotocol without using headers directly.
 */
public class ServerUpgradeResponseImpl extends Response.Wrapper implements ServerUpgradeResponse
{
    private final Response response;
    private final WebSocketNegotiation negotiation;
    private final HttpFields.Mutable fields;

    public ServerUpgradeResponseImpl(WebSocketNegotiation negotiation, Response baseResponse)
    {
        super(baseResponse.getRequest(), baseResponse);
        this.negotiation = negotiation;
        this.response = baseResponse;
        this.fields = new WebSocketHttpFieldsWrapper(response.getHeaders(), this, negotiation);
    }

    @Override
    public HttpFields.Mutable getHeaders()
    {
        return fields;
    }

    @Override
    public String getAcceptedSubProtocol()
    {
        return negotiation.getSubprotocol();
    }

    @Override
    public void setAcceptedSubProtocol(String protocol)
    {
        negotiation.setSubprotocol(protocol);
        // Use the nested headers to avoid recursion.
        response.getHeaders().put(HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL, protocol);
    }

    @Override
    public List<ExtensionConfig> getExtensions()
    {
        return negotiation.getNegotiatedExtensions();
    }

    @Override
    public void addExtensions(List<ExtensionConfig> configs)
    {
        ArrayList<ExtensionConfig> combinedConfig = new ArrayList<>();
        combinedConfig.addAll(getExtensions());
        combinedConfig.addAll(configs);
        setExtensions(combinedConfig);
    }

    @Override
    public void setExtensions(List<ExtensionConfig> configs)
    {
        // This validation is also done later in RFC6455Handshaker but it is better to fail earlier
        for (ExtensionConfig config : configs)
        {
            if (config.getName().startsWith("@"))
                continue;

            long matches = negotiation.getOfferedExtensions().stream().filter(e -> e.getName().equals(config.getName())).count();
            if (matches < 1)
                throw new IllegalArgumentException("Extension not a requested extension");

            matches = configs.stream().filter(e -> e.getName().equals(config.getName())).count();
            if (matches > 1)
                throw new IllegalArgumentException("Multiple extensions of the same name");
        }

        negotiation.setNegotiatedExtensions(configs);
    }

    public String toString()
    {
        return String.format("UpgradeResponse=%s", response);
    }
}
