//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
import java.net.URI;
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
import org.eclipse.jetty.websocket.api.WebSocketConstants;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;

/**
 * Servlet specific {@link UpgradeRequest} implementation.
 */
public class ServletUpgradeRequest implements UpgradeRequest
{
    private static final String CANNOT_MODIFY_SERVLET_REQUEST = "Cannot modify Servlet Request";
    private final URI requestURI;
    private final String queryString;
    private final UpgradeHttpServletRequest request;
    private final boolean secure;
    private List<HttpCookie> cookies;
    private Map<String, List<String>> parameterMap;

    public ServletUpgradeRequest(HttpServletRequest httpRequest) throws URISyntaxException
    {
        this.queryString = httpRequest.getQueryString();
        this.secure = httpRequest.isSecure();

        StringBuffer uri = httpRequest.getRequestURL();
        if (this.queryString != null)
            uri.append("?").append(this.queryString);
        uri.replace(0, uri.indexOf(":"), secure ? "wss" : "ws");
        this.requestURI = new URI(uri.toString());
        this.request = new UpgradeHttpServletRequest(httpRequest);
    }

    @Override
    public void addExtensions(ExtensionConfig... configs)
    {
        throw new UnsupportedOperationException(CANNOT_MODIFY_SERVLET_REQUEST);
    }

    @Override
    public void addExtensions(String... configs)
    {
        throw new UnsupportedOperationException(CANNOT_MODIFY_SERVLET_REQUEST);
    }

    @Override
    public void clearHeaders()
    {
        throw new UnsupportedOperationException(CANNOT_MODIFY_SERVLET_REQUEST);
    }

    public void complete()
    {
        request.complete();
    }

    @SuppressWarnings("unused")
    public X509Certificate[] getCertificates()
    {
        return (X509Certificate[])request.getAttribute("javax.servlet.request.X509Certificate");
    }

    @Override
    public List<HttpCookie> getCookies()
    {
        if (cookies == null)
        {
            Cookie[] requestCookies = request.getCookies();
            if (requestCookies != null)
            {
                cookies = new ArrayList<>();
                for (Cookie requestCookie : requestCookies)
                {
                    HttpCookie cookie = new HttpCookie(requestCookie.getName(), requestCookie.getValue());
                    // No point handling domain/path/expires/secure/httponly on client request cookies
                    cookies.add(cookie);
                }
            }
        }

        return cookies;
    }

    @Override
    public List<ExtensionConfig> getExtensions()
    {
        Enumeration<String> e = request.getHeaders("Sec-WebSocket-Extensions");
        return ExtensionConfig.parseEnum(e);
    }

    @Override
    public String getHeader(String name)
    {
        return request.getHeader(name);
    }

    @Override
    public int getHeaderInt(String name)
    {
        String val = request.getHeader(name);
        if (val == null)
        {
            return -1;
        }
        return Integer.parseInt(val);
    }

    @Override
    public Map<String, List<String>> getHeaders()
    {
        return request.getHeaders();
    }

    @Override
    public List<String> getHeaders(String name)
    {
        return request.getHeaders().get(name);
    }

    @Override
    public String getHost()
    {
        return requestURI.getHost();
    }

    /**
     * Return the underlying HttpServletRequest that existed at Upgrade time.
     * <p>
     * Note: many features of the HttpServletRequest are invalid when upgraded,
     * especially ones that deal with body content, streams, readers, and responses.
     *
     * @return a limited version of the underlying HttpServletRequest
     */
    public HttpServletRequest getHttpServletRequest()
    {
        return request;
    }

    @Override
    public String getHttpVersion()
    {
        return request.getProtocol();
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
     * <p>
     * Warning: this can cause a DNS lookup
     *
     * @return the local socket address
     */
    public InetSocketAddress getLocalSocketAddress()
    {
        return new InetSocketAddress(getLocalAddress(), getLocalPort());
    }

    @Override
    public String getMethod()
    {
        return request.getMethod();
    }

    @Override
    public String getOrigin()
    {
        return getHeader("Origin");
    }

    @Override
    public Map<String, List<String>> getParameterMap()
    {
        if (parameterMap == null)
        {
            Map<String, String[]> requestParams = request.getParameterMap();
            if (requestParams != null)
            {
                parameterMap = new HashMap<>(requestParams.size());
                for (Map.Entry<String, String[]> entry : requestParams.entrySet())
                {
                    parameterMap.put(entry.getKey(), Arrays.asList(entry.getValue()));
                }
            }
        }
        return parameterMap;
    }

    /**
     * @return the principal
     * @deprecated use {@link #getUserPrincipal()} instead
     */
    @Deprecated
    public Principal getPrincipal()
    {
        return getUserPrincipal();
    }

    @Override
    public String getProtocolVersion()
    {
        String version = request.getHeader(WebSocketConstants.SEC_WEBSOCKET_VERSION);
        if (version == null)
        {
            return Integer.toString(WebSocketConstants.SPEC_VERSION);
        }
        return version;
    }

    @Override
    public String getQueryString()
    {
        return this.queryString;
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
     * <p>
     * Warning: this can cause a DNS lookup
     *
     * @return the remote socket address
     */
    public InetSocketAddress getRemoteSocketAddress()
    {
        return new InetSocketAddress(getRemoteAddress(), getRemotePort());
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

    @Override
    public URI getRequestURI()
    {
        return requestURI;
    }

    public Object getServletAttribute(String name)
    {
        return request.getAttribute(name);
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
     * <p>
     * Note: this is equivalent to {@link HttpServletRequest#getSession(boolean)}
     * and will not create a new HttpSession.
     */
    @Override
    public HttpSession getSession()
    {
        return request.getSession(false);
    }

    @Override
    public List<String> getSubProtocols()
    {
        Enumeration<String> requestProtocols = request.getHeaders("Sec-WebSocket-Protocol");
        if (requestProtocols != null && requestProtocols.hasMoreElements())
        {
            ArrayList subprotocols = new ArrayList<>(2);
            while (requestProtocols.hasMoreElements())
            {
                String candidate = requestProtocols.nextElement();
                Collections.addAll(subprotocols, parseProtocols(candidate));
            }
            return subprotocols;
        }
        else
        {
            return Collections.emptyList();
        }
    }

    /**
     * Equivalent to {@link HttpServletRequest#getUserPrincipal()}
     */
    @Override
    public Principal getUserPrincipal()
    {
        return request.getUserPrincipal();
    }

    @Override
    public boolean hasSubProtocol(String test)
    {
        for (String protocol : getSubProtocols())
        {
            if (protocol.equalsIgnoreCase(test))
            {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isOrigin(String test)
    {
        return test.equalsIgnoreCase(getOrigin());
    }

    @Override
    public boolean isSecure()
    {
        return this.secure;
    }

    public boolean isUserInRole(String role)
    {
        return request.isUserInRole(role);
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

    @Override
    public void setCookies(List<HttpCookie> cookies)
    {
        throw new UnsupportedOperationException(CANNOT_MODIFY_SERVLET_REQUEST);
    }

    @Override
    public void setExtensions(List<ExtensionConfig> configs)
    {
        throw new UnsupportedOperationException(CANNOT_MODIFY_SERVLET_REQUEST);
    }

    @Override
    public void setHeader(String name, List<String> values)
    {
        throw new UnsupportedOperationException(CANNOT_MODIFY_SERVLET_REQUEST);
    }

    @Override
    public void setHeader(String name, String value)
    {
        throw new UnsupportedOperationException(CANNOT_MODIFY_SERVLET_REQUEST);
    }

    @Override
    public void setHeaders(Map<String, List<String>> headers)
    {
        throw new UnsupportedOperationException(CANNOT_MODIFY_SERVLET_REQUEST);
    }

    @Override
    public void setHttpVersion(String httpVersion)
    {
        throw new UnsupportedOperationException(CANNOT_MODIFY_SERVLET_REQUEST);
    }

    @Override
    public void setMethod(String method)
    {
        throw new UnsupportedOperationException(CANNOT_MODIFY_SERVLET_REQUEST);
    }

    @Override
    public void setRequestURI(URI uri)
    {
        throw new UnsupportedOperationException(CANNOT_MODIFY_SERVLET_REQUEST);
    }

    public void setServletAttribute(String name, Object value)
    {
        request.setAttribute(name, value);
    }

    @Override
    public void setSession(Object session)
    {
        throw new UnsupportedOperationException(CANNOT_MODIFY_SERVLET_REQUEST);
    }

    @Override
    public void setSubProtocols(List<String> subProtocols)
    {
        throw new UnsupportedOperationException(CANNOT_MODIFY_SERVLET_REQUEST);
    }

    @Override
    public void setSubProtocols(String... protocols)
    {
        throw new UnsupportedOperationException(CANNOT_MODIFY_SERVLET_REQUEST);
    }
}
