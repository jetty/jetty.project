//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.servlet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.websocket.common.HandshakeResponse;
import org.eclipse.jetty.websocket.core.WebSocketConstants;
import org.eclipse.jetty.websocket.core.extensions.ExtensionConfig;

/**
 * Servlet Specific UpgradeResponse implementation.
 */
public class ServletUpgradeResponse implements HandshakeResponse
{
    private final HttpServletResponse response;
    private boolean extensionsNegotiated = false;
    private boolean subprotocolNegotiated = false;
    private List<ExtensionConfig> extensions = new ArrayList<>();

    public ServletUpgradeResponse(HttpServletResponse response)
    {
        Objects.requireNonNull(response, "HttpServletResponse must not be null");
        this.response = response;
    }

    public void addHeader(String name, String value)
    {
        response.addHeader(name, value);
    }

    public void setHeader(String name, String value)
    {
        response.setHeader(name, value);
    }

    public void setHeader(String name, List<String> values)
    {
        response.setHeader(name, null); // clear it out first
        values.forEach((value)->response.addHeader(name, value));
    }

    @Override
    public String getAcceptedSubProtocol()
    {
        return getHeader(WebSocketConstants.SEC_WEBSOCKET_PROTOCOL);
    }

    @Override
    public List<ExtensionConfig> getExtensions()
    {
        return extensions;
    }

    @Override
    public String getHeader(String name)
    {
        return response.getHeader(name);
    }

    @Override
    public Set<String> getHeaderNames()
    {
        return Collections.unmodifiableSet(new HashSet<>(response.getHeaderNames()));
    }

    @Override
    public Map<String, List<String>> getHeadersMap()
    {
        Map<String, List<String>> headers = response.getHeaderNames().stream()
                .collect(Collectors.toMap((name) -> name,
                        (name) -> new ArrayList<>(response.getHeaders(name))));
        return Collections.unmodifiableMap(headers);
    }

    @Override
    public List<String> getHeaders(String name)
    {
        return Collections.unmodifiableList(new ArrayList<>(response.getHeaders(name)));
    }

    @Override
    public int getStatusCode()
    {
        return response.getStatus();
    }

    public boolean isCommitted()
    {
        return response.isCommitted();
    }

    public boolean isExtensionsNegotiated()
    {
        return extensionsNegotiated;
    }

    public boolean isSubprotocolNegotiated()
    {
        return subprotocolNegotiated;
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
        response.setHeader(WebSocketConstants.SEC_WEBSOCKET_PROTOCOL, protocol);
        subprotocolNegotiated = true;
    }

    public void setExtensions(List<ExtensionConfig> configs)
    {
        this.extensions.clear();
        this.extensions.addAll(configs);
        String value = ExtensionConfig.toHeaderValue(configs);
        response.setHeader(WebSocketConstants.SEC_WEBSOCKET_EXTENSIONS, value);
        extensionsNegotiated = true;
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
