//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.websocket.api.UpgradeResponse;
import org.eclipse.jetty.websocket.api.WebSocketConstants;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;

/**
 * Servlet Specific UpgradeResponse implementation.
 */
public class ServletUpgradeResponse implements UpgradeResponse
{
    private HttpServletResponse response;
    private boolean extensionsNegotiated = false;
    private boolean subprotocolNegotiated = false;
    private Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private List<ExtensionConfig> extensions = new ArrayList<>();
    private boolean success = false;
    
    public ServletUpgradeResponse(HttpServletResponse response)
    {
        this.response = response;
    
        for (String name : response.getHeaderNames())
        {
            headers.put(name, new ArrayList<String>(response.getHeaders(name)));
        }
    }
    
    @Override
    public void addHeader(String name, String value)
    {
        this.response.addHeader(name, value);
    }
    
    private void commitHeaders()
    {
        // Transfer all headers to the real HTTP response
        for (Map.Entry<String, List<String>> entry : getHeaders().entrySet())
        {
            for (String value : entry.getValue())
            {
                response.addHeader(entry.getKey(), value);
            }
        }
    }
    
    public void complete()
    {
        commitHeaders();
        response = null;
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
        return getHeaders().keySet();
    }
    
    @Override
    public Map<String, List<String>> getHeaders()
    {
        return headers;
    }
    
    @Override
    public List<String> getHeaders(String name)
    {
        return getHeaders().get(name);
    }
    
    @Override
    public int getStatusCode()
    {
        return response.getStatus();
    }
    
    @Override
    public String getStatusReason()
    {
        throw new UnsupportedOperationException("Servlet's do not support Status Reason");
    }
    
    public boolean isCommitted()
    {
        if (response != null)
        {
            return response.isCommitted();
        }
        // True in all other cases
        return true;
    }
    
    public boolean isExtensionsNegotiated()
    {
        return extensionsNegotiated;
    }
    
    public boolean isSubprotocolNegotiated()
    {
        return subprotocolNegotiated;
    }
    
    @Override
    public boolean isSuccess()
    {
        return success;
    }
    
    public void sendError(int statusCode, String message) throws IOException
    {
        setSuccess(false);
        applyHeaders();
        response.sendError(statusCode, message);
        response.flushBuffer(); // commit response
        response = null;
    }
    
    @Override
    public void sendForbidden(String message) throws IOException
    {
        setSuccess(false);
        applyHeaders();
        response.sendError(HttpServletResponse.SC_FORBIDDEN, message);
        response.flushBuffer(); // commit response
        response = null;
    }
    
    @Override
    public void setAcceptedSubProtocol(String protocol)
    {
        response.setHeader(WebSocketConstants.SEC_WEBSOCKET_PROTOCOL, protocol);
        subprotocolNegotiated = true;
    }
    
    @Override
    public void setExtensions(List<ExtensionConfig> configs)
    {
        this.extensions.clear();
        this.extensions.addAll(configs);
        String value = ExtensionConfig.toHeaderValue(configs);
        response.setHeader(WebSocketConstants.SEC_WEBSOCKET_EXTENSIONS, value);
        extensionsNegotiated = true;
    }
    
    @Override
    public void setHeader(String name, String value)
    {
        response.setHeader(name, value);
    }
    
    private void applyHeaders()
    {
        // Transfer all headers to the real HTTP response
        for (Map.Entry<String, List<String>> entry : getHeaders().entrySet())
        {
            for (String value : entry.getValue())
            {
                response.addHeader(entry.getKey(), value);
            }
        }
    }
    
    public void setStatus(int status)
    {
        response.setStatus(status);
    }
    
    @Override
    public void setStatusCode(int statusCode)
    {
        response.setStatus(statusCode);
    }
    
    @Override
    public void setStatusReason(String statusReason)
    {
        throw new UnsupportedOperationException("Servlet's do not support Status Reason");
    }
    
    @Override
    public void setSuccess(boolean success)
    {
        this.success = success;
    }
}
