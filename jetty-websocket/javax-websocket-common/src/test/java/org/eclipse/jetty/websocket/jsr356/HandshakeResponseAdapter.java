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

package org.eclipse.jetty.websocket.jsr356;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.eclipse.jetty.websocket.common.HandshakeResponse;
import org.eclipse.jetty.websocket.core.WebSocketConstants;
import org.eclipse.jetty.websocket.core.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.core.util.QuoteUtil;

public class HandshakeResponseAdapter implements HandshakeResponse
{
    public static final String SEC_WEBSOCKET_PROTOCOL = WebSocketConstants.SEC_WEBSOCKET_PROTOCOL;
    private int statusCode;
    private String statusReason;
    private Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private List<ExtensionConfig> extensions = new ArrayList<>();
    private boolean success = false;

    /**
     * Get the accepted WebSocket protocol.
     * 
     * @return the accepted WebSocket protocol.
     */
    @Override
    public String getAcceptedSubProtocol()
    {
        return getHeader(SEC_WEBSOCKET_PROTOCOL);
    }

    /**
     * Get the list of extensions that should be used for the websocket.
     * 
     * @return the list of negotiated extensions to use.
     */
    @Override
    public List<ExtensionConfig> getExtensions()
    {
        return extensions;
    }

    @Override
    public String getHeader(String name)
    {
        List<String> values = getHeaders(name);
        // no value list
        if (values == null)
        {
            return null;
        }
        int size = values.size();
        // empty value list
        if (size <= 0)
        {
            return null;
        }
        // simple return
        if (size == 1)
        {
            return values.get(0);
        }
        // join it with commas
        boolean needsDelim = false;
        StringBuilder ret = new StringBuilder();
        for (String value : values)
        {
            if (needsDelim)
            {
                ret.append(", ");
            }
            QuoteUtil.quoteIfNeeded(ret,value,QuoteUtil.ABNF_REQUIRED_QUOTING);
            needsDelim = true;
        }
        return ret.toString();
    }

    @Override
    public Set<String> getHeaderNames()
    {
        return headers.keySet();
    }

    @Override
    public Map<String, List<String>> getHeadersMap()
    {
        return headers;
    }

    @Override
    public List<String> getHeaders(String name)
    {
        return headers.get(name);
    }

    @Override
    public int getStatusCode()
    {
        return statusCode;
    }
}
