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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.QuotedCSV;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.ExtensionConfig;
import org.eclipse.jetty.websocket.core.WebSocketComponents;

public abstract class WebSocketNegotiation
{
    private final Request request;
    private final Response response;
    private final Callback callback;
    private final WebSocketComponents components;
    private String version;
    private List<ExtensionConfig> offeredExtensions;
    private List<ExtensionConfig> negotiatedExtensions;
    private List<String> offeredProtocols;
    private String protocol;

    public WebSocketNegotiation(Request request, Response response, Callback callback, WebSocketComponents webSocketComponents)
    {
        this.request = request;
        this.response = response;
        this.callback = callback;
        this.components = webSocketComponents;
    }

    public Request getRequest()
    {
        return request;
    }

    public Response getResponse()
    {
        return response;
    }

    public Callback getCallback()
    {
        return callback;
    }

    public void negotiate() throws BadMessageException
    {
        try
        {
            negotiateHeaders(request);
        }
        catch (Throwable x)
        {
            throw new BadMessageException("Invalid upgrade request", x);
        }
    }

    protected void negotiateHeaders(Request baseRequest)
    {
        QuotedCSV extensions = null;
        QuotedCSV protocols = null;
        for (HttpField field : baseRequest.getHeaders())
        {
            if (field.getHeader() != null)
            {
                switch (field.getHeader())
                {
                    case SEC_WEBSOCKET_VERSION:
                        version = field.getValue();
                        break;

                    case SEC_WEBSOCKET_EXTENSIONS:
                        if (extensions == null)
                            extensions = new QuotedCSV(field.getValue());
                        else
                            extensions.addValue(field.getValue());
                        break;

                    case SEC_WEBSOCKET_SUBPROTOCOL:
                        if (protocols == null)
                            protocols = new QuotedCSV(field.getValue());
                        else
                            protocols.addValue(field.getValue());
                        break;

                    default:
                        break;
                }
            }
        }

        Set<String> available = components.getExtensionRegistry().getAvailableExtensionNames();
        offeredExtensions = extensions == null
            ? Collections.emptyList()
            : extensions.getValues().stream()
            .map(ExtensionConfig::parse)
            .filter(ec -> available.contains(ec.getName()) && !ec.getName().startsWith("@"))
            .collect(Collectors.toList());

        // Remove any parameters starting with "@", these are not to be negotiated by client (internal parameters).
        offeredExtensions.forEach(ExtensionConfig::removeInternalParameters);

        offeredProtocols = protocols == null
            ? Collections.emptyList()
            : protocols.getValues();

        negotiatedExtensions = new ArrayList<>();
        for (ExtensionConfig config : offeredExtensions)
        {
            long matches = negotiatedExtensions.stream()
                .filter(negotiatedConfig -> negotiatedConfig.getName().equals(config.getName())).count();
            if (matches == 0)
                negotiatedExtensions.add(new ExtensionConfig(config));
        }
    }

    public abstract boolean validateHeaders();

    public String getVersion()
    {
        return version;
    }

    public String getSubprotocol()
    {
        return protocol;
    }

    public void setSubprotocol(String protocol)
    {
        this.protocol = protocol;
        response.getHeaders().put(HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL, protocol);
    }

    public List<String> getOfferedSubprotocols()
    {
        return offeredProtocols;
    }

    public List<ExtensionConfig> getOfferedExtensions()
    {
        return offeredExtensions;
    }

    public List<ExtensionConfig> getNegotiatedExtensions()
    {
        return negotiatedExtensions;
    }

    public void setNegotiatedExtensions(List<ExtensionConfig> extensions)
    {
        if (extensions == offeredExtensions)
            return;
        negotiatedExtensions = extensions;
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{uri=%s,oe=%s,op=%s}",
            getClass().getSimpleName(),
            hashCode(),
            getRequest().getHttpURI(),
            getOfferedExtensions(),
            getOfferedSubprotocols());
    }
}
