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

package org.eclipse.jetty.websocket.core.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.QuotedCSV;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.websocket.core.Behavior;
import org.eclipse.jetty.websocket.core.ExtensionConfig;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.internal.ExtensionStack;

public abstract class Negotiation
{
    private final Request baseRequest;
    private final HttpServletRequest request;
    private final HttpServletResponse response;
    private final WebSocketComponents components;
    private String version;
    private List<ExtensionConfig> offeredExtensions;
    private List<ExtensionConfig> negotiatedExtensions;
    private List<String> offeredProtocols;
    private ExtensionStack extensionStack;
    private String protocol;

    public Negotiation(Request baseRequest, HttpServletRequest request, HttpServletResponse response, WebSocketComponents webSocketComponents)
    {
        this.baseRequest = baseRequest;
        this.request = request;
        this.response = response;
        this.components = webSocketComponents;
    }

    public Request getBaseRequest()
    {
        return baseRequest;
    }

    public HttpServletRequest getRequest()
    {
        return request;
    }

    public HttpServletResponse getResponse()
    {
        return response;
    }

    public void negotiate() throws BadMessageException
    {
        try
        {
            negotiateHeaders(getBaseRequest());
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
        for (HttpField field : baseRequest.getHttpFields())
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
            .filter(ec -> available.contains(ec.getName().toLowerCase()) && !ec.getName().startsWith("@"))
            .collect(Collectors.toList());

        offeredProtocols = protocols == null
            ? Collections.emptyList()
            : protocols.getValues();

        negotiatedExtensions = new ArrayList<>();
        for (ExtensionConfig config : offeredExtensions)
        {
            long matches = negotiatedExtensions.stream()
                .filter(negotiatedConfig -> negotiatedConfig.getName().equals(config.getName())).count();
            if (matches == 0)
                negotiatedExtensions.add(config);
        }
    }

    public abstract boolean isSuccessful();

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
        response.setHeader(HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL.asString(), protocol);
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
        extensionStack = null;
    }

    public ExtensionStack getExtensionStack()
    {
        if (extensionStack == null)
        {
            // Extension stack can decide to drop any of these extensions or their parameters
            extensionStack = new ExtensionStack(components, Behavior.SERVER);
            extensionStack.negotiate(offeredExtensions, negotiatedExtensions);
            negotiatedExtensions = extensionStack.getNegotiatedExtensions();

            if (extensionStack.hasNegotiatedExtensions())
                baseRequest.getResponse().setHeader(HttpHeader.SEC_WEBSOCKET_EXTENSIONS,
                    ExtensionConfig.toHeaderValue(negotiatedExtensions));
            else
                baseRequest.getResponse().setHeader(HttpHeader.SEC_WEBSOCKET_EXTENSIONS, null);
        }
        return extensionStack;
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{uri=%s,oe=%s,op=%s}",
            getClass().getSimpleName(),
            hashCode(),
            getRequest().getRequestURI(),
            getOfferedExtensions(),
            getOfferedSubprotocols());
    }
}
