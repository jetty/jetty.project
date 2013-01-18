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

package org.eclipse.jetty.websocket.server;

import java.security.Principal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.api.util.QuoteUtil;

public class ServletWebSocketRequest extends UpgradeRequest
{
    private Map<String, String> cookieMap;
    private HttpServletRequest req;

    public ServletWebSocketRequest(HttpServletRequest request)
    {
        super(request.getRequestURI());
        this.req = request;

        // Copy Request Line Details
        setMethod(request.getMethod());
        setHttpVersion(request.getProtocol());

        // Copy Cookies
        cookieMap = new HashMap<String, String>();
        for (Cookie cookie : request.getCookies())
        {
            cookieMap.put(cookie.getName(),cookie.getValue());
        }

        // Copy Headers
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements())
        {
            String name = headerNames.nextElement();
            Enumeration<String> valuesEnum = request.getHeaders(name);
            List<String> values = new ArrayList<>();
            while (valuesEnum.hasMoreElements())
            {
                values.add(valuesEnum.nextElement());
            }
            setHeader(name,values);
        }

        // Parse Sub Protocols
        Enumeration<String> protocols = request.getHeaders("Sec-WebSocket-Protocol");
        List<String> subProtocols = new ArrayList<>();
        String protocol = null;
        while ((protocol == null) && (protocols != null) && protocols.hasMoreElements())
        {
            String candidate = protocols.nextElement();
            for (String p : parseProtocols(candidate))
            {
                subProtocols.add(p);
            }
        }
        setSubProtocols(subProtocols);

        // Parse Extension Configurations
        Enumeration<String> e = request.getHeaders("Sec-WebSocket-Extensions");
        while (e.hasMoreElements())
        {
            Iterator<String> extTokenIter = QuoteUtil.splitAt(e.nextElement(),",");
            while (extTokenIter.hasNext())
            {
                String extToken = extTokenIter.next();
                ExtensionConfig config = ExtensionConfig.parse(extToken);
                addExtensions(config);
            }
        }
    }

    protected String[] parseProtocols(String protocol)
    {
        if (protocol == null)
        {
            return new String[]
            { null };
        }
        protocol = protocol.trim();
        if ((protocol == null) || (protocol.length() == 0))
        {
            return new String[]
            { null };
        }
        String[] passed = protocol.split("\\s*,\\s*");
        String[] protocols = new String[passed.length + 1];
        System.arraycopy(passed,0,protocols,0,passed.length);
        return protocols;
    }

    public void setServletAttribute(String name, Object o)
    {
        this.req.setAttribute(name,o);
    }
    
    public Principal getPrincipal()
    {
        return req.getUserPrincipal();
    }
    
    public StringBuffer getRequestURL()
    {
        return req.getRequestURL();
    }
    
    public Map<String, Object> getServletAttributes()
    {
        Map<String, Object> attributes = new HashMap<String,Object>();
        
        for (String name : Collections.list((Enumeration<String>)req.getAttributeNames()))
        {
            attributes.put(name, req.getAttribute(name));
        }
        
        return attributes;
    }
    
    public Map<String, List<String>> getServletParameters()
    {
        Map<String, List<String>> parameters = new HashMap<String, List<String>>();
        
        for (String name : Collections.list((Enumeration<String>)req.getParameterNames()))
        {
            parameters.put(name, Collections.unmodifiableList(Arrays.asList(req.getParameterValues(name))));
        }
        
        return parameters;
    }   
}
