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
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.websocket.core.ExtensionConfig;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class Negotiation
{
    private final Request baseRequest;
    private final HttpServletRequest request;
    private final HttpServletResponse response;
    private final List<ExtensionConfig> offeredExtensions;
    private final List<String> offeredSubprotocols;
    private int version;
    private Boolean upgrade;
    private String key = null;
    
    private int errorCode;
    private String errorReason;

    public Negotiation(Request baseRequest, HttpServletRequest request, HttpServletResponse response, Set<String> availableExtensions)
    {
        this.baseRequest = baseRequest;
        this.request = request;
        this.response = response;
        
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
                        version = field.getIntValue();
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
        
        if (upgrade==null || connectionCSVs==null || !connectionCSVs.getValues().stream().anyMatch(s->s.equalsIgnoreCase("Upgrade")))
            upgrade = Boolean.FALSE;
        
        offeredExtensions = extensions==null
            ?Collections.emptyList()
            :extensions.getValues().stream()
            .map(ExtensionConfig::parse)
            .filter(ec->availableExtensions.contains(ec.getName().toLowerCase()))
            .collect(Collectors.toList());
        setNegotiatedExtensions(offeredExtensions);
        
        offeredSubprotocols = subprotocols==null
            ?Collections.emptyList()
            :subprotocols.getValues();
    }

    public int getErrorCode()
    {
        return errorCode;
    }

    public String getErrorReason()
    {
        return errorReason;
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
        response.setHeader(HttpHeader.SEC_WEBSOCKET_EXTENSIONS.asString(),ExtensionConfig.toHeaderValue(extensions));
    }

    public List<ExtensionConfig> getNegotiatedExtensions()
    {
        // TODO, if we know that setNegotiatedExtensions is always called, we can remember the parsed list!
        return baseRequest.getResponse().getHttpFields()
            .getCSV(HttpHeader.SEC_WEBSOCKET_EXTENSIONS,true)
            .stream()
            .map(ExtensionConfig::parse)
            .collect(Collectors.toList());
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
        response.setHeader(HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL.asString(),subprotocol);
    }

    public String getSubprotocol()
    {
        return response.getHeader(HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL.asString());
    }

    public int getVersion()
    {
        return version;
    }

    public boolean isError()
    {
        return errorCode>0;
    }

    public boolean isUpgrade()
    {
        return upgrade;
    }

    public void sendError(int code, String reason)
    {
        errorCode = code;
        errorReason = reason;
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
