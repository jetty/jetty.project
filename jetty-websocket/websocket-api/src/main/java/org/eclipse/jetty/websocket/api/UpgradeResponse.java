//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.api;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.api.util.QuoteUtil;

public class UpgradeResponse
{
    public static final String SEC_WEBSOCKET_PROTOCOL = "Sec-WebSocket-Protocol";
    private int statusCode;
    private String statusReason;
    private Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private List<ExtensionConfig> extensions = new ArrayList<>();
    private boolean success = false;

    public void addHeader(String name, String value)
    {
        String key = name;
        List<String> values = headers.get(key);
        if (values == null)
        {
            values = new ArrayList<>();
        }
        values.add(value);
        headers.put(key,values);
    }

    /**
     * Get the accepted WebSocket protocol.
     * 
     * @return the accepted WebSocket protocol.
     */
    public String getAcceptedSubProtocol()
    {
        return getHeader(SEC_WEBSOCKET_PROTOCOL);
    }

    /**
     * Get the list of extensions that should be used for the websocket.
     * 
     * @return the list of negotiated extensions to use.
     */
    public List<ExtensionConfig> getExtensions()
    {
        return extensions;
    }

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

    public Set<String> getHeaderNames()
    {
        return headers.keySet();
    }

    public Map<String, List<String>> getHeaders()
    {
        return headers;
    }

    public List<String> getHeaders(String name)
    {
        return headers.get(name);
    }

    public int getStatusCode()
    {
        return statusCode;
    }

    public String getStatusReason()
    {
        return statusReason;
    }

    public boolean isSuccess()
    {
        return success;
    }

    /**
     * Issue a forbidden upgrade response.
     * <p>
     * This means that the websocket endpoint was valid, but the conditions to use a WebSocket resulted in a forbidden access.
     * <p>
     * Use this when the origin or authentication is invalid.
     * 
     * @param message
     *            the short 1 line detail message about the forbidden response
     * @throws IOException
     */
    public void sendForbidden(String message) throws IOException
    {
        throw new UnsupportedOperationException("Not supported");
    }

    /**
     * Set the accepted WebSocket Protocol.
     * 
     * @param protocol
     *            the protocol to list as accepted
     */
    public void setAcceptedSubProtocol(String protocol)
    {
        setHeader(SEC_WEBSOCKET_PROTOCOL,protocol);
    }

    /**
     * Set the list of extensions that are approved for use with this websocket.
     * <p>
     * Notes:
     * <ul>
     * <li>Per the spec you cannot add extensions that have not been seen in the {@link UpgradeRequest}, just remove entries you don't want to use</li>
     * <li>If this is unused, or a null is passed, then the list negotiation will follow default behavior and use the complete list of extensions that are
     * available in this WebSocket server implementation.</li>
     * </ul>
     * 
     * @param extensions
     *            the list of extensions to use.
     */
    public void setExtensions(List<ExtensionConfig> extensions)
    {
        this.extensions.clear();
        if (extensions != null)
        {
            this.extensions.addAll(extensions);
        }
    }

    public void setHeader(String name, String value)
    {
        List<String> values = new ArrayList<>();
        values.add(value);
        headers.put(name,values);
    }

    public void setStatusCode(int statusCode)
    {
        this.statusCode = statusCode;
    }

    public void setStatusReason(String statusReason)
    {
        this.statusReason = statusReason;
    }

    public void setSuccess(boolean success)
    {
        this.success = success;
    }
}
