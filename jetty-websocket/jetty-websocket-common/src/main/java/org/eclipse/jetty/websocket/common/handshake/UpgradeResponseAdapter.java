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

package org.eclipse.jetty.websocket.common.handshake;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.QuotedCSV;
import org.eclipse.jetty.websocket.api.UpgradeResponse;
import org.eclipse.jetty.websocket.core.ExtensionConfig;

@Deprecated
public class UpgradeResponseAdapter implements UpgradeResponse
{
    public static final String SEC_WEBSOCKET_PROTOCOL = HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL.asString();
    private int statusCode;
    private String statusReason;
    private Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private List<ExtensionConfig> extensions = new ArrayList<>();
    private boolean success = false;

    @Override
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
    @Override
    public String getAcceptedSubProtocol()
    {
        return getHeader(SEC_WEBSOCKET_PROTOCOL);
    }

    @Override
    public String getHeader(String name)
    {
        List<String> values = getHeaders(name);
        return QuotedCSV.join(values);
    }

    @Override
    public Set<String> getHeaderNames()
    {
        return headers.keySet();
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

    /**
     * Issue a forbidden upgrade response.
     * <p>
     * This means that the websocket endpoint was valid, but the conditions to use a WebSocket resulted in a forbidden
     * access.
     * <p>
     * Use this when the origin or authentication is invalid.
     * 
     * @param message
     *            the short 1 line detail message about the forbidden response
     * @throws IOException
     *             if unable to send the forbidden
     */
    @Override
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
    @Override
    public void setAcceptedSubProtocol(String protocol)
    {
        setHeader(SEC_WEBSOCKET_PROTOCOL,protocol);
    }

    @Override
    public void setHeader(String name, String value)
    {
        List<String> values = new ArrayList<>();
        values.add(value);
        headers.put(name,values);
    }

    @Override
    public List<org.eclipse.jetty.websocket.api.extensions.ExtensionConfig> getExtensions()
    {
        // TODO
        return null;
    }

    @Override
    public Map<String, List<String>> getHeaders()
    {
        // TODO
        return null;
    }

    @Override
    public String getStatusReason()
    {
        // TODO
        return null;
    }

    @Override
    public boolean isSuccess()
    {
        // TODO
        return false;
    }

    @Override
    public void setExtensions(List<org.eclipse.jetty.websocket.api.extensions.ExtensionConfig> extensions)
    {
        // TODO
    }

    @Override
    public void setStatusCode(int statusCode)
    {
        // TODO
    }

    @Override
    public void setStatusReason(String statusReason)
    {
        // TODO
    }

    @Override
    public void setSuccess(boolean success)
    {
        // TODO
    }
}
