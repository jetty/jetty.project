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

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.QuotedCSV;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.extensions.ExtensionConfig;

public class Negotiation
{
    private final HttpServletRequest request;
    private final HttpServletResponse response;
    private final List<ExtensionConfig> offeredExtensions;
    private final List<String> offeredSubprotocols;
    private List<ExtensionConfig> negotiatedExtensions;
    private WebSocketPolicy policy;
    private int version;
    private Boolean upgrade;
    private String key = null;
    
    private int errorCode;
    private String errorReason;

    public Negotiation(Request baseRequest, HttpServletRequest request, HttpServletResponse response)
    {
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
            :extensions.getValues().stream().map(ExtensionConfig::parse).collect(Collectors.toList());
        
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

    public List<ExtensionConfig> getNegotiatedExtensions()
    {
        return negotiatedExtensions;
    }

    public void setNegotiatedExtensions(List<ExtensionConfig> extensions)
    {
        negotiatedExtensions = extensions;
    }

    public List<String> getOfferedSubprotocols()
    {
        return offeredSubprotocols;
    }

    public WebSocketPolicy getPolicy()
    {
        return policy;
    }

    public HttpServletRequest getRequest()
    {
        return request;
    }
    
    public HttpServletResponse getResponse()
    {
        return response;
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

    public void setPolicy(WebSocketPolicy policy)
    {
        this.policy = policy;
    }

    public void setSubprotocol(String subprotocol)
    {
        response.setHeader(HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL.asString(),subprotocol);
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
