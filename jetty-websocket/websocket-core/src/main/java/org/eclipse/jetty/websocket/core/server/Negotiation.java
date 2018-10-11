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

package org.eclipse.jetty.websocket.core.server;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.QuotedCSV;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.websocket.core.ExtensionConfig;
import org.eclipse.jetty.websocket.core.WebSocketExtensionRegistry;
import org.eclipse.jetty.websocket.core.internal.ExtensionStack;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;
import java.util.stream.Collectors;

public class Negotiation
{
    private final Request baseRequest;
    private final HttpServletRequest request;
    private final HttpServletResponse response;
    private final List<ExtensionConfig> offeredExtensions;
    private final List<String> offeredSubprotocols;
    private final WebSocketExtensionRegistry registry;
    private final DecoratedObjectFactory objectFactory;
    private final ByteBufferPool bufferPool;
    private final String version;
    private final Boolean upgrade;
    private final String key;

    private List<ExtensionConfig> negotiatedExtensions;
    private String subprotocol;
    private ExtensionStack extensionStack;

    public Negotiation(
        Request baseRequest,
        HttpServletRequest request,
        HttpServletResponse response,
        WebSocketExtensionRegistry registry,
        DecoratedObjectFactory objectFactory,
        ByteBufferPool bufferPool)
    {
        this.baseRequest = baseRequest;
        this.request = request;
        this.response = response;
        this.registry = registry;
        this.objectFactory = objectFactory;
        this.bufferPool = bufferPool;

        Boolean upgrade = null;
        String key = null;
        String version = null;
        QuotedCSV connectionCSVs = null;
        QuotedCSV extensions = null;
        QuotedCSV subprotocols = null;
        
        for (HttpField field : baseRequest.getHttpFields())
        {            
            if (field.getHeader()!=null)
            {
                switch(field.getHeader())
                {
                    case UPGRADE:
                        if (upgrade==null && "websocket".equalsIgnoreCase(field.getValue()))
                            upgrade = Boolean.TRUE;
                        break;

                    case CONNECTION:
                        if (connectionCSVs==null)
                            connectionCSVs = new QuotedCSV();
                        connectionCSVs.addValue(field.getValue());
                        break;

                    case SEC_WEBSOCKET_KEY:
                        key = field.getValue();
                        break;

                    case SEC_WEBSOCKET_VERSION:
                        version = field.getValue();
                        break;
                        
                    case SEC_WEBSOCKET_EXTENSIONS:
                        if (extensions==null)
                            extensions = new QuotedCSV(field.getValue());
                        else
                            extensions.addValue(field.getValue());
                        break;
                        
                    case SEC_WEBSOCKET_SUBPROTOCOL:
                        if (subprotocols==null)
                            subprotocols = new QuotedCSV(field.getValue());
                        else
                            subprotocols.addValue(field.getValue());
                        break;

                    default:
                }
            }
        }

        this.version = version;
        this.key = key;
        this.upgrade = upgrade!=null && connectionCSVs!=null && connectionCSVs.getValues().stream().anyMatch(s->s.equalsIgnoreCase("Upgrade"));

        Set<String> available = registry.getAvailableExtensionNames();
        offeredExtensions = extensions==null
            ?Collections.emptyList()
            :extensions.getValues().stream()
            .map(ExtensionConfig::parse)
            .filter(ec->available.contains(ec.getName().toLowerCase()))
            .collect(Collectors.toList());

        offeredSubprotocols = subprotocols==null
            ?Collections.emptyList()
            :subprotocols.getValues();
    }
    
    public String getKey()
    {
        return key;
    }

    public List<ExtensionConfig> getOfferedExtensions()
    {
        return offeredExtensions;
    }

    public void setNegotiatedExtensions(List<ExtensionConfig> extensions)
    {
        if (extensions==offeredExtensions)
            return;
        negotiatedExtensions = extensions==null?null:new ArrayList<>(extensions);
        extensionStack = null;
    }

    public List<ExtensionConfig> getNegotiatedExtensions()
    {
        if (negotiatedExtensions==null)
            return offeredExtensions;
        return negotiatedExtensions;
    }

    public List<String> getOfferedSubprotocols()
    {
        return offeredSubprotocols;
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

    public void setSubprotocol(String subprotocol)
    {
        this.subprotocol = subprotocol;
        response.setHeader(HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL.asString(),subprotocol);
    }

    public String getSubprotocol()
    {
        return subprotocol;
    }

    public String getVersion()
    {
        return version;
    }

    public boolean isUpgrade()
    {
        return upgrade;
    }

    public ExtensionStack getExtensionStack()
    {
        if (extensionStack == null)
        {
            extensionStack = new ExtensionStack(registry);
            boolean configsFromApplication = true;

            if (negotiatedExtensions == null)
            {
                // Has the header been set directly?
                List<String> extensions = baseRequest.getResponse().getHttpFields()
                    .getCSV(HttpHeader.SEC_WEBSOCKET_EXTENSIONS,true);

                if (extensions.isEmpty())
                {
                    // If the negotiatedExtensions has not been set, just use the offered extensions
                    negotiatedExtensions = new ArrayList(offeredExtensions);
                    configsFromApplication = false;
                }
                else
                {
                    negotiatedExtensions = extensions
                        .stream()
                        .map(ExtensionConfig::parse)
                        .collect(Collectors.toList());
                }
            }

            if (configsFromApplication)
            {
                // TODO is this really necessary?
                // Replace any configuration in the negotiated extensions with the offered extensions config
                for (ListIterator<ExtensionConfig> i = negotiatedExtensions.listIterator(); i.hasNext();)
                {
                    ExtensionConfig config = i.next();
                    offeredExtensions.stream().filter(c->c.getName().equalsIgnoreCase(config.getName()))
                        .findFirst()
                        .ifPresent(i::set);
                }
            }

            extensionStack.negotiate(objectFactory, bufferPool, negotiatedExtensions);
            negotiatedExtensions = extensionStack.getNegotiatedExtensions();
            if (extensionStack.hasNegotiatedExtensions())
                baseRequest.getResponse().setHeader(HttpHeader.SEC_WEBSOCKET_EXTENSIONS,
                    ExtensionConfig.toHeaderValue(negotiatedExtensions));
            else
                baseRequest.getResponse().setHeader(HttpHeader.SEC_WEBSOCKET_EXTENSIONS,null);
        }
        return extensionStack;
    }


    @Override
    public String toString()
    {
        return String.format("Negotiation@%x{uri=%s,oe=%s,op=%s}",
            hashCode(),
            getRequest().getRequestURI(),
            getOfferedExtensions(),
            getOfferedSubprotocols());
    }

}
