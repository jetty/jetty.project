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
import java.util.Collection;
import java.util.HashSet;
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
    private int status;
    
    public ServletUpgradeResponse(HttpServletResponse response)
    {
        this.response = response;
    }
    
    @Override
    public void addHeader(String name, String value)
    {
        if (value!=null)
        {
            List<String> values = headers.get(name);
            if (values==null)
            {
                values = new ArrayList<>();
                headers.put(name,values);
            }
            values.add(value);
        }
    }

    @Override
    public void setHeader(String name, String value)
    {
        // remove from the real response
        if (response!=null)
            response.setHeader(name,null);

       
        List<String> values = headers.get(name);
        if (values==null)
        {
            values = new ArrayList<>();
            headers.put(name,values);
        }
        else
            values.clear();
        values.add(value);
    }
    
    public void complete()
    {
        if (response==null)
            return;

        // Take a copy of all the real response headers
        Map<String, Collection<String>> real = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String name : response.getHeaderNames())
        {
            real.put(name,response.getHeaders(name));
        }
        
        // Transfer all headers to the real HTTP response
        for (Map.Entry<String, List<String>> entry : getHeaders().entrySet())
        {
            for (String value : entry.getValue())
            {
                response.addHeader(entry.getKey(), value);
            }
        }
        
        // Prepend the real headers to the copy headers
        for (Map.Entry<String, Collection<String>> entry : real.entrySet())
        {
            String name = entry.getKey();
            Collection<String> prepend = entry.getValue();
            List<String> values = headers.getOrDefault(name,headers.containsKey(name)?null:new ArrayList<>());
            values.addAll(0,prepend);
        }
        
        status = response.getStatus();
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
        if (response!=null)
        {
            String value = response.getHeader(name);
            if (value!=null)
                return value;
        }
        List<String> values = headers.get(name);
        if (values!=null && !values.isEmpty())
            return values.get(0);
        return null;
    }
    
    @Override
    public Set<String> getHeaderNames()
    {
        if (response==null)
            return headers.keySet();
        
        Set<String> h = new HashSet<>(response.getHeaderNames());
        h.addAll(headers.keySet());
        return h;
    }
    
    @Override
    public Map<String, List<String>> getHeaders()
    {
        return headers;
    }
    
    @Override
    public List<String> getHeaders(String name)
    {
        if (response==null)
            return headers.get(name);
        
        List<String> values = new ArrayList<>(response.getHeaders(name));
        values.addAll(headers.get(name));
        return values.isEmpty()?null:values;
    }
    
    @Override
    public int getStatusCode()
    {
        if (response!=null)
            return response.getStatus();
        return status;
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
        HttpServletResponse r = response;
        complete();
        r.sendError(statusCode, message);
        r.flushBuffer();
    }
    
    @Override
    public void sendForbidden(String message) throws IOException
    {
        setSuccess(false);
        HttpServletResponse r = response;
        complete();
        r.sendError(HttpServletResponse.SC_FORBIDDEN, message);
        r.flushBuffer();
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
    public void setStatusCode(int statusCode)
    {
        if (response!=null)
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
    
    @Override
    public String toString()
    {
        return String.format("r=%s s=%d h=%s",response,status,headers);
    }
}
