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

package org.eclipse.jetty.websocket.servlet;

import java.net.HttpCookie;
import java.net.InetSocketAddress;
import java.net.URISyntaxException;
import java.security.Principal;
import java.security.cert.X509Certificate;
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
import org.eclipse.jetty.websocket.api.util.WSURI;

/**
 * Servlet specific {@link UpgradeRequest} implementation.
 */
public class ServletUpgradeRequest extends UpgradeRequest
{
    private HttpServletRequest req;

    public ServletUpgradeRequest(HttpServletRequest request) throws URISyntaxException
    {
        super(WSURI.toWebsocket(request.getRequestURL(),request.getQueryString()));
        this.req = request;

        // Copy Request Line Details
        setMethod(request.getMethod());
        setHttpVersion(request.getProtocol());

        // Copy parameters
        Map<String, List<String>> pmap = new HashMap<>();
        if (request.getParameterMap() != null)
        {
            for (Map.Entry<String, String[]> entry : request.getParameterMap().entrySet())
            {
                pmap.put(entry.getKey(),Arrays.asList(entry.getValue()));
            }
        }
        super.setParameterMap(pmap);

        // Copy Cookies
        Cookie rcookies[] = request.getCookies();
        if (rcookies != null)
        {
            List<HttpCookie> cookies = new ArrayList<>();
            for (Cookie rcookie : rcookies)
            {
                HttpCookie hcookie = new HttpCookie(rcookie.getName(),rcookie.getValue());
                // no point handling domain/path/expires/secure/httponly on client request cookies
                cookies.add(hcookie);
            }
            super.setCookies(cookies);
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

    public X509Certificate[] getCertificates()
    {
        return (X509Certificate[])req.getAttribute("javax.servlet.request.X509Certificate");
    }

    /**
     * Equivalent to {@link HttpServletRequest#getLocalAddr()}
     * 
     * @return the local address
     */
    public String getLocalAddress()
    {
        return req.getLocalAddr();
    }

    /**
     * Equivalent to {@link HttpServletRequest#getLocalName()}
     * 
     * @return the local host name
     */
    public String getLocalHostName()
    {
        return req.getLocalName();
    }

    /**
     * Equivalent to {@link HttpServletRequest#getLocalPort()}
     * 
     * @return the local port
     */
    public int getLocalPort()
    {
        return req.getLocalPort();
    }

    /**
     * Return a {@link InetSocketAddress} for the local socket.
     * <p>
     * Warning: this can cause a DNS lookup
     * 
     * @return the local socket address
     */
    public InetSocketAddress getLocalSocketAddress()
    {
        return new InetSocketAddress(req.getLocalAddr(),req.getLocalPort());
    }

    public Principal getPrincipal()
    {
        return req.getUserPrincipal();
    }

    /**
     * Equivalent to {@link HttpServletRequest#getRemoteAddr()}
     * 
     * @return the remote address
     */
    public String getRemoteAddress()
    {
        return req.getRemoteAddr();
    }

    /**
     * Equivalent to {@link HttpServletRequest#getRemoteHost()}
     * 
     * @return the remote host name
     */
    public String getRemoteHostName()
    {
        return req.getRemoteHost();
    }

    /**
     * Equivalent to {@link HttpServletRequest#getRemotePort()}
     * 
     * @return the remote port
     */
    public int getRemotePort()
    {
        return req.getRemotePort();
    }

    /**
     * Return a {@link InetSocketAddress} for the remote socket.
     * <p>
     * Warning: this can cause a DNS lookup
     * 
     * @return the remote socket address
     */
    public InetSocketAddress getRemoteSocketAddress()
    {
        return new InetSocketAddress(req.getRemoteAddr(),req.getRemotePort());
    }

    public Map<String, Object> getServletAttributes()
    {
        Map<String, Object> attributes = new HashMap<String, Object>();

        for (String name : Collections.list(req.getAttributeNames()))
        {
            attributes.put(name,req.getAttribute(name));
        }

        return attributes;
    }

    public Map<String, List<String>> getServletParameters()
    {
        Map<String, List<String>> parameters = new HashMap<String, List<String>>();

        for (String name : Collections.list(req.getParameterNames()))
        {
            parameters.put(name,Collections.unmodifiableList(Arrays.asList(req.getParameterValues(name))));
        }

        return parameters;
    }

    /**
     * Return the HttpSession if it exists.
     * <p>
     * Note: this is equivalent to {@link HttpServletRequest#getSession()} and will not create a new HttpSession.
     */
    @Override
    public Object getSession()
    {
        return this.req.getSession(false);
    }

    protected String[] parseProtocols(String protocol)
    {
        if (protocol == null)
        {
            return new String[] {};
        }
        protocol = protocol.trim();
        if ((protocol == null) || (protocol.length() == 0))
        {
            return new String[] {};
        }
        String[] passed = protocol.split("\\s*,\\s*");
        String[] protocols = new String[passed.length];
        System.arraycopy(passed,0,protocols,0,passed.length);
        return protocols;
    }

    public void setServletAttribute(String name, Object o)
    {
        this.req.setAttribute(name,o);
    }

}
