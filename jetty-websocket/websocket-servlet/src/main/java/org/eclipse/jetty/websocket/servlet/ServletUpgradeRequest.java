//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.api.util.WSURI;

/**
 * Servlet specific {@link UpgradeRequest} implementation.
 */
public class ServletUpgradeRequest extends UpgradeRequest
{
    private final UpgradeHttpServletRequest request;

    public ServletUpgradeRequest(HttpServletRequest httpRequest) throws URISyntaxException
    {
        super(WSURI.toWebsocket(httpRequest.getRequestURL(), httpRequest.getQueryString()));
        this.request = new UpgradeHttpServletRequest(httpRequest);

        // Parse protocols.
        Enumeration<String> requestProtocols = request.getHeaders("Sec-WebSocket-Protocol");
        if (requestProtocols != null)
        {
            List<String> protocols = new ArrayList<>(2);
            while (requestProtocols.hasMoreElements())
            {
                String candidate = requestProtocols.nextElement();
                Collections.addAll(protocols, parseProtocols(candidate));
            }
            setSubProtocols(protocols);
        }

        // Parse extensions.
        Enumeration<String> e = request.getHeaders("Sec-WebSocket-Extensions");
        setExtensions(ExtensionConfig.parseEnum(e));

        // Copy cookies.
        Cookie[] requestCookies = request.getCookies();
        if (requestCookies != null)
        {
            List<HttpCookie> cookies = new ArrayList<>();
            for (Cookie requestCookie : requestCookies)
            {
                HttpCookie cookie = new HttpCookie(requestCookie.getName(), requestCookie.getValue());
                // No point handling domain/path/expires/secure/httponly on client request cookies
                cookies.add(cookie);
            }
            setCookies(cookies);
        }

        setHeaders(request.getHeaders());

        // Copy parameters.
        Map<String, String[]> requestParams = request.getParameterMap();
        if (requestParams != null)
        {
            Map<String, List<String>> params = new HashMap<>(requestParams.size());
            for (Map.Entry<String, String[]> entry : requestParams.entrySet())
                params.put(entry.getKey(), Arrays.asList(entry.getValue()));
            setParameterMap(params);
        }

        setSession(request.getSession(false));

        setHttpVersion(request.getProtocol());
        setMethod(request.getMethod());
    }

    public X509Certificate[] getCertificates()
    {
        return (X509Certificate[])request.getAttribute("javax.servlet.request.X509Certificate");
    }

    /**
     * Return the underlying HttpServletRequest that existed at Upgrade time.
     * <p/>
     * Note: many features of the HttpServletRequest are invalid when upgraded,
     * especially ones that deal with body content, streams, readers, and responses.
     *
     * @return a limited version of the underlying HttpServletRequest
     */
    public HttpServletRequest getHttpServletRequest()
    {
        return request;
    }

    /**
     * Equivalent to {@link HttpServletRequest#getLocalAddr()}
     *
     * @return the local address
     */
    public String getLocalAddress()
    {
        return request.getLocalAddr();
    }

    /**
     * Equivalent to {@link HttpServletRequest#getLocalName()}
     *
     * @return the local host name
     */
    public String getLocalHostName()
    {
        return request.getLocalName();
    }

    /**
     * Equivalent to {@link HttpServletRequest#getLocalPort()}
     *
     * @return the local port
     */
    public int getLocalPort()
    {
        return request.getLocalPort();
    }

    /**
     * Return a {@link InetSocketAddress} for the local socket.
     * <p/>
     * Warning: this can cause a DNS lookup
     *
     * @return the local socket address
     */
    public InetSocketAddress getLocalSocketAddress()
    {
        return new InetSocketAddress(getLocalAddress(), getLocalPort());
    }

    /**
     * Equivalent to {@link HttpServletRequest#getLocale()}
     *
     * @return the preferred <code>Locale</code> for the client
     */
    public Locale getLocale()
    {
        return request.getLocale();
    }

    /**
     * Equivalent to {@link HttpServletRequest#getLocales()}
     *
     * @return an Enumeration of preferred Locale objects
     */
    public Enumeration<Locale> getLocales()
    {
        return request.getLocales();
    }

    /**
     * @deprecated use {@link #getUserPrincipal()} instead
     */
    @Deprecated
    public Principal getPrincipal()
    {
        return getUserPrincipal();
    }

    /**
     * Equivalent to {@link HttpServletRequest#getUserPrincipal()}
     */
    public Principal getUserPrincipal()
    {
        return request.getUserPrincipal();
    }

    /**
     * Equivalent to {@link HttpServletRequest#getRemoteAddr()}
     *
     * @return the remote address
     */
    public String getRemoteAddress()
    {
        return request.getRemoteAddr();
    }

    /**
     * Equivalent to {@link HttpServletRequest#getRemoteHost()}
     *
     * @return the remote host name
     */
    public String getRemoteHostName()
    {
        return request.getRemoteHost();
    }

    /**
     * Equivalent to {@link HttpServletRequest#getRemotePort()}
     *
     * @return the remote port
     */
    public int getRemotePort()
    {
        return request.getRemotePort();
    }

    /**
     * Return a {@link InetSocketAddress} for the remote socket.
     * <p/>
     * Warning: this can cause a DNS lookup
     *
     * @return the remote socket address
     */
    public InetSocketAddress getRemoteSocketAddress()
    {
        return new InetSocketAddress(getRemoteAddress(), getRemotePort());
    }

    public Map<String, Object> getServletAttributes()
    {
        return request.getAttributes();
    }

    public Map<String, List<String>> getServletParameters()
    {
        return getParameterMap();
    }

    /**
     * Return the HttpSession if it exists.
     * <p/>
     * Note: this is equivalent to {@link HttpServletRequest#getSession(boolean)}
     * and will not create a new HttpSession.
     */
    @Override
    public HttpSession getSession()
    {
        return request.getSession(false);
    }

    public void setServletAttribute(String name, Object value)
    {
        request.setAttribute(name, value);
    }

    public Object getServletAttribute(String name)
    {
        return request.getAttribute(name);
    }

    public boolean isUserInRole(String role)
    {
        return request.isUserInRole(role);
    }

    public String getRequestPath()
    {
        // Since this can be called from a filter, we need to be smart about determining the target request path.
        String contextPath = request.getContextPath();
        String requestPath = request.getRequestURI();
        if (requestPath.startsWith(contextPath))
            requestPath = requestPath.substring(contextPath.length());
        return requestPath;
    }

    private String[] parseProtocols(String protocol)
    {
        if (protocol == null)
            return new String[0];
        protocol = protocol.trim();
        if (protocol.length() == 0)
            return new String[0];
        return protocol.split("\\s*,\\s*");
    }

    public void complete()
    {
        request.complete();
    }
}
