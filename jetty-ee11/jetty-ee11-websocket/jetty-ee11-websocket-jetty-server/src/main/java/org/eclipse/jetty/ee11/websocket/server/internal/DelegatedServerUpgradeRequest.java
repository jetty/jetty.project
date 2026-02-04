//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee11.websocket.server.internal;

import java.net.HttpCookie;
import java.net.SocketAddress;
import java.net.URI;
import java.security.Principal;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.eclipse.jetty.ee11.websocket.server.JettyServerUpgradeRequest;
import org.eclipse.jetty.http.HttpException;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.websocket.api.ExtensionConfig;
import org.eclipse.jetty.websocket.common.JettyExtensionConfig;
import org.eclipse.jetty.websocket.core.WebSocketConstants;
import org.eclipse.jetty.websocket.core.server.ServerUpgradeRequest;

/**
 * Implements the {@link JettyServerUpgradeRequest} interface by delegating to the core {@link ServerUpgradeRequest}.
 * <p>
 * This class is to be used during the WebSocket negotiation phase on the server side.
 */
public class DelegatedServerUpgradeRequest implements JettyServerUpgradeRequest
{
    private final HttpFields _httpFields;
    private final ServerUpgradeRequest _upgradeRequest;
    private final HttpServletRequest _httpServletRequest;

    private URI _requestURI;
    private List<HttpCookie> _cookies;
    private Map<String, List<String>> _parameterMap;

    public DelegatedServerUpgradeRequest(ServerUpgradeRequest request)
    {
        _httpFields = request.getHeaders();
        _httpServletRequest = (HttpServletRequest)request
            .getAttribute(WebSocketConstants.WEBSOCKET_WRAPPED_REQUEST_ATTRIBUTE);
        _upgradeRequest = request;
    }

    public ServerUpgradeRequest getServerUpgradeRequest()
    {
        return _upgradeRequest;
    }

    @Override
    public List<HttpCookie> getCookies()
    {
        if (_cookies == null)
        {
            Cookie[] reqCookies = _httpServletRequest.getCookies();
            if (reqCookies != null)
            {
                _cookies = Arrays.stream(reqCookies)
                    .map(c -> new HttpCookie(c.getName(), c.getValue()))
                    .collect(Collectors.toList());
            }
            else
            {
                _cookies = Collections.emptyList();
            }
        }

        return _cookies;
    }

    @Override
    public List<ExtensionConfig> getExtensions()
    {
        return _upgradeRequest.getExtensions().stream()
            .map(JettyExtensionConfig::new)
            .collect(Collectors.toList());
    }

    @Override
    public String getHeader(String name)
    {
        return _httpFields.get(name);
    }

    @Override
    public int getHeaderInt(String name)
    {
        HttpField field = _httpFields.getField(name);
        return (field == null) ? -1 : field.getIntValue();
    }

    @Override
    public Map<String, List<String>> getHeaders()
    {
        return HttpFields.asMap(_httpFields);
    }

    @Override
    public List<String> getHeaders(String name)
    {
        return _httpFields.getValuesList(name);
    }

    @Override
    public String getHost()
    {
        return _upgradeRequest.getHttpURI().getHost();
    }

    @Override
    public String getHttpVersion()
    {
        return _upgradeRequest.getConnectionMetaData().getHttpVersion().asString();
    }

    @Override
    public String getMethod()
    {
        return _httpServletRequest.getMethod();
    }

    @Override
    public String getOrigin()
    {
        return _httpServletRequest.getHeader(HttpHeader.ORIGIN.asString());
    }

    @Override
    public Map<String, List<String>> getParameterMap()
    {
        if (_parameterMap == null)
        {
            Map<String, String[]> requestParams = _httpServletRequest.getParameterMap();
            if (requestParams != null)
            {
                _parameterMap = new HashMap<>(requestParams.size());
                for (Map.Entry<String, String[]> entry : requestParams.entrySet())
                {
                    _parameterMap.put(entry.getKey(), Arrays.asList(entry.getValue()));
                }
            }
        }
        return _parameterMap;
    }

    @Override
    public String getProtocolVersion()
    {
        return _upgradeRequest.getProtocolVersion();
    }

    @Override
    public String getQueryString()
    {
        return _httpServletRequest.getQueryString();
    }

    @Override
    public URI getRequestURI()
    {
        if (_requestURI == null)
        {
            try
            {
                String queryString = _httpServletRequest.getQueryString();
                StringBuffer uri = _httpServletRequest.getRequestURL();
                if (queryString != null)
                    uri.append("?").append(queryString);
                uri.replace(0, uri.indexOf(":"), _httpServletRequest.isSecure() ? "wss" : "ws");
                _requestURI = new URI(uri.toString());
            }
            catch (Throwable t)
            {
                throw new HttpException.IllegalStateException(HttpStatus.BAD_REQUEST_400, "Bad WebSocket UpgradeRequest URI", t);
            }
        }

        return _requestURI;
    }

    @Override
    public HttpSession getSession()
    {
        return _httpServletRequest.getSession();
    }

    @Override
    public List<String> getSubProtocols()
    {
        return _upgradeRequest.getSubProtocols();
    }

    @Override
    public Principal getUserPrincipal()
    {
        return _httpServletRequest.getUserPrincipal();
    }

    @Override
    public boolean hasSubProtocol(String subprotocol)
    {
        return _upgradeRequest.hasSubProtocol(subprotocol);
    }

    @Override
    public boolean isSecure()
    {
        return _httpServletRequest.isSecure();
    }

    @Override
    public X509Certificate[] getCertificates()
    {
        return (X509Certificate[])_httpServletRequest.getAttribute("jakarta.servlet.request.X509Certificate");
    }

    @Override
    public HttpServletRequest getHttpServletRequest()
    {
        return _httpServletRequest;
    }

    @Override
    public Locale getLocale()
    {
        return _httpServletRequest.getLocale();
    }

    @Override
    public Enumeration<Locale> getLocales()
    {
        return _httpServletRequest.getLocales();
    }

    @Override
    public SocketAddress getLocalSocketAddress()
    {
        return _upgradeRequest.getConnectionMetaData().getLocalSocketAddress();
    }

    @Override
    public SocketAddress getRemoteSocketAddress()
    {
        return _upgradeRequest.getConnectionMetaData().getRemoteSocketAddress();
    }

    @Override
    public String getRequestPath()
    {
        return URIUtil.addPaths(_httpServletRequest.getServletPath(), _httpServletRequest.getPathInfo());
    }

    @Override
    public Object getServletAttribute(String name)
    {
        return _upgradeRequest.getAttribute(name);
    }

    @Override
    public Map<String, Object> getServletAttributes()
    {
        Map<String, Object> attributes = new HashMap<>(2);
        Enumeration<String> attributeNames = _httpServletRequest.getAttributeNames();
        while (attributeNames.hasMoreElements())
        {
            String name = attributeNames.nextElement();
            attributes.put(name, _httpServletRequest.getAttribute(name));
        }
        return attributes;
    }

    @Override
    public Map<String, List<String>> getServletParameters()
    {
        return getParameterMap();
    }

    @Override
    public boolean isUserInRole(String role)
    {
        return _httpServletRequest.isUserInRole(role);
    }

    @Override
    public void setServletAttribute(String name, Object value)
    {
        _upgradeRequest.setAttribute(name, value);
    }
}
