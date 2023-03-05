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
import java.util.Set;

import org.eclipse.jetty.http.BadMessage;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.websocket.core.ExtensionConfig;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.WebSocketConstants;
import org.eclipse.jetty.websocket.core.server.ServerUpgradeRequest;

/**
 * Upgrade request used for websocket negotiation.
 * Provides getters for things like the requested extensions and subprotocols so that the headers don't have to be parsed manually.
 */
public class ServerUpgradeRequestImpl extends Request.Wrapper implements ServerUpgradeRequest
{
    private final Request request;
    private final WebSocketNegotiation negotiation;
    private final Attributes attributes = new Attributes.Lazy();
    private boolean upgraded = false;

    public ServerUpgradeRequestImpl(WebSocketNegotiation negotiation, Request baseRequest) throws BadMessage.RuntimeException
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
    public void upgrade(Attributes attributes)
    {
        this.attributes.clearAttributes();
        for (String name : attributes.getAttributeNameSet())
        {
            this.attributes.setAttribute(name, attributes.getAttribute(name));
        }
        upgraded = true;
    }

    @Override
    public Object removeAttribute(String name)
    {
        if (upgraded)
            return attributes.removeAttribute(name);
        return super.removeAttribute(name);
    }

    @Override
    public Object setAttribute(String name, Object attribute)
    {
        if (upgraded)
            return attributes.setAttribute(name, attribute);
        return super.setAttribute(name, attribute);
    }

    @Override
    public Object getAttribute(String name)
    {
        if (upgraded)
            return attributes.getAttribute(name);
        return super.getAttribute(name);
    }

    @Override
    public Set<String> getAttributeNameSet()
    {
        if (upgraded)
            return attributes.getAttributeNameSet();
        return super.getAttributeNameSet();
    }

    @Override
    public void clearAttributes()
    {
        if (upgraded)
            attributes.clearAttributes();
        else
            super.clearAttributes();
    }

    /**
     * @return The extensions offered
     */
    @Override
    public List<ExtensionConfig> getExtensions()
    {
        return negotiation.getOfferedExtensions();
    }

    /**
     * @return WebSocket protocol version from "Sec-WebSocket-Version" header
     */
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

    /**
     * @return Get WebSocket negotiation offered sub protocols
     */
    @Override
    public List<String> getSubProtocols()
    {
        return negotiation.getOfferedSubprotocols();
    }

    /**
     * @param subprotocol A sub protocol name
     * @return True if the sub protocol was offered
     */
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
