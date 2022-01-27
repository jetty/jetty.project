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

package org.eclipse.jetty.websocket.core.server;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.websocket.core.ExtensionConfig;

/**
 * Upgrade response used for websocket negotiation.
 * Allows setting of extensions and subprotocol without using headers directly.
 */
public class ServerUpgradeResponse
{
    private final HttpServletResponse response;
    private final WebSocketNegotiation negotiation;

    public ServerUpgradeResponse(WebSocketNegotiation negotiation)
    {
        this.negotiation = negotiation;
        this.response = negotiation.getResponse();
        Objects.requireNonNull(response, "HttpServletResponse must not be null");
    }

    public void addHeader(String name, String value)
    {
        if (HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL.is(name))
        {
            setAcceptedSubProtocol(value);
            return;
        }

        if (HttpHeader.SEC_WEBSOCKET_EXTENSIONS.is(name))
        {
            addExtensions(ExtensionConfig.parseList(value));
            return;
        }

        response.addHeader(name, value);
    }

    public void setHeader(String name, String value)
    {
        if (HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL.is(name))
        {
            setAcceptedSubProtocol(value);
            return;
        }

        if (HttpHeader.SEC_WEBSOCKET_EXTENSIONS.is(name))
        {
            setExtensions(ExtensionConfig.parseList(value));
            return;
        }

        response.setHeader(name, value);
    }

    public void setHeader(String name, List<String> values)
    {
        if (HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL.is(name))
        {
            if (values == null || values.isEmpty())
                setAcceptedSubProtocol(null);
            else if (values.size() == 1)
                setAcceptedSubProtocol(values.get(0));
            else
                throw new IllegalArgumentException("multiple subprotocols");
        }

        if (HttpHeader.SEC_WEBSOCKET_EXTENSIONS.is(name))
        {
            List<ExtensionConfig> extensions = Collections.emptyList();
            if (values != null)
            {
                extensions = values.stream()
                    .flatMap(s -> ExtensionConfig.parseList(s).stream())
                    .collect(Collectors.toList());
            }

            setExtensions(extensions);
            return;
        }

        response.setHeader(name, null);
        if (values != null)
            values.forEach(value -> response.addHeader(name, value));
    }

    public String getAcceptedSubProtocol()
    {
        return getHeader(HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL.asString());
    }

    public List<ExtensionConfig> getExtensions()
    {
        return negotiation.getNegotiatedExtensions();
    }

    public String getHeader(String name)
    {
        return response.getHeader(name);
    }

    public Set<String> getHeaderNames()
    {
        return Set.copyOf(response.getHeaderNames());
    }

    public Map<String, List<String>> getHeadersMap()
    {
        Map<String, List<String>> headers = response.getHeaderNames().stream()
            .collect(Collectors.toMap((name) -> name, (name) -> new ArrayList<>(response.getHeaders(name))));
        return Collections.unmodifiableMap(headers);
    }

    public List<String> getHeaders(String name)
    {
        return List.copyOf(response.getHeaders(name));
    }

    public int getStatusCode()
    {
        return response.getStatus();
    }

    public boolean isCommitted()
    {
        return response.isCommitted();
    }

    public void sendError(int statusCode, String message) throws IOException
    {
        response.sendError(statusCode, message);
        response.flushBuffer();
    }

    public void sendForbidden(String message) throws IOException
    {
        sendError(HttpServletResponse.SC_FORBIDDEN, message);
    }

    public void setAcceptedSubProtocol(String protocol)
    {
        negotiation.setSubprotocol(protocol);
    }

    public void addExtensions(List<ExtensionConfig> configs)
    {
        ArrayList<ExtensionConfig> combinedConfig = new ArrayList<>();
        combinedConfig.addAll(getExtensions());
        combinedConfig.addAll(configs);
        setExtensions(combinedConfig);
    }

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

    public void setStatusCode(int statusCode)
    {
        response.setStatus(statusCode);
    }

    public String toString()
    {
        return String.format("UpgradeResponse=%s", response);
    }
}
