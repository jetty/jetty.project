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

package org.eclipse.jetty.websocket.common;

import java.net.HttpCookie;
import java.net.URI;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.api.util.QuoteUtil;

public class UpgradeRequestAdapter implements UpgradeRequest
{
    private URI requestURI;
    private List<String> subProtocols = new ArrayList<>(1);
    private List<ExtensionConfig> extensions = new ArrayList<>(1);
    private List<HttpCookie> cookies = new ArrayList<>(1);
    private Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private Map<String, List<String>> parameters = new HashMap<>(1);
    private Object session;
    private String httpVersion;
    private String method;
    private String host;
    private boolean secure;

    protected UpgradeRequestAdapter()
    {
        /* anonymous, no requestURI, upgrade request */
    }

    public UpgradeRequestAdapter(String requestURI)
    {
        this(URI.create(requestURI));
    }

    public UpgradeRequestAdapter(URI requestURI)
    {
        setRequestURI(requestURI);
    }

    @Override
    public void addExtensions(ExtensionConfig... configs)
    {
        Collections.addAll(extensions, configs);
    }

    @Override
    public void addExtensions(String... configs)
    {
        for (String config : configs)
        {
            extensions.add(ExtensionConfig.parse(config));
        }
    }

    @Override
    public void clearHeaders()
    {
        headers.clear();
    }

    @Override
    public List<HttpCookie> getCookies()
    {
        return cookies;
    }

    @Override
    public List<ExtensionConfig> getExtensions()
    {
        return extensions;
    }

    @Override
    public String getHeader(String name)
    {
        List<String> values = headers.get(name);
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
            QuoteUtil.quoteIfNeeded(ret, value, QuoteUtil.ABNF_REQUIRED_QUOTING);
            needsDelim = true;
        }
        return ret.toString();
    }

    @Override
    public int getHeaderInt(String name)
    {
        List<String> values = headers.get(name);
        // no value list
        if (values == null)
        {
            return -1;
        }
        int size = values.size();
        // empty value list
        if (size <= 0)
        {
            return -1;
        }
        // simple return
        if (size == 1)
        {
            return Integer.parseInt(values.get(0));
        }
        throw new NumberFormatException("Cannot convert multi-value header into int");
    }

    @Override
    public Map<String, List<String>> getHeaders()
    {
        return headers;
    }

    @Override
    public List<String> getHeaders(String name)
    {
        return headers.get(name);
    }

    @Override
    public String getHost()
    {
        return host;
    }

    @Override
    public String getHttpVersion()
    {
        return httpVersion;
    }

    @Override
    public String getMethod()
    {
        return method;
    }

    @Override
    public String getOrigin()
    {
        return getHeader("Origin");
    }

    /**
     * Returns a map of the query parameters of the request.
     *
     * @return a unmodifiable map of query parameters of the request.
     */
    @Override
    public Map<String, List<String>> getParameterMap()
    {
        return Collections.unmodifiableMap(parameters);
    }

    @Override
    public String getProtocolVersion()
    {
        String version = getHeader("Sec-WebSocket-Version");
        if (version == null)
        {
            return "13"; // Default
        }
        return version;
    }

    @Override
    public String getQueryString()
    {
        return requestURI.getQuery();
    }

    @Override
    public URI getRequestURI()
    {
        return requestURI;
    }

    /**
     * Access the Servlet HTTP Session (if present)
     * <p>
     * Note: Never present on a Client UpgradeRequest.
     *
     * @return the Servlet HTTPSession on server side UpgradeRequests
     */
    @Override
    public Object getSession()
    {
        return session;
    }

    @Override
    public List<String> getSubProtocols()
    {
        return subProtocols;
    }

    /**
     * Get the User Principal for this request.
     * <p>
     * Only applicable when using UpgradeRequest from server side.
     *
     * @return the user principal
     */
    @Override
    public Principal getUserPrincipal()
    {
        // Server side should override to implement
        return null;
    }

    @Override
    public boolean hasSubProtocol(String test)
    {
        for (String protocol : subProtocols)
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
        return secure;
    }

    @Override
    public void setCookies(List<HttpCookie> cookies)
    {
        this.cookies.clear();
        if (cookies != null && !cookies.isEmpty())
        {
            this.cookies.addAll(cookies);
        }
    }

    @Override
    public void setExtensions(List<ExtensionConfig> configs)
    {
        this.extensions.clear();
        if (configs != null)
        {
            this.extensions.addAll(configs);
        }
    }

    @Override
    public void setHeader(String name, List<String> values)
    {
        headers.put(name, values);
    }

    @Override
    public void setHeader(String name, String value)
    {
        List<String> values = new ArrayList<>();
        values.add(value);
        setHeader(name, values);
    }

    @Override
    public void setHeaders(Map<String, List<String>> headers)
    {
        clearHeaders();

        for (Map.Entry<String, List<String>> entry : headers.entrySet())
        {
            String name = entry.getKey();
            List<String> values = entry.getValue();
            setHeader(name, values);
        }
    }

    @Override
    public void setHttpVersion(String httpVersion)
    {
        this.httpVersion = httpVersion;
    }

    @Override
    public void setMethod(String method)
    {
        this.method = method;
    }

    protected void setParameterMap(Map<String, List<String>> parameters)
    {
        this.parameters.clear();
        this.parameters.putAll(parameters);
    }

    @Override
    public void setRequestURI(URI uri)
    {
        this.requestURI = uri;
        String scheme = uri.getScheme();
        if ("ws".equalsIgnoreCase(scheme))
        {
            secure = false;
        }
        else if ("wss".equalsIgnoreCase(scheme))
        {
            secure = true;
        }
        else
        {
            throw new IllegalArgumentException("URI scheme must be 'ws' or 'wss'");
        }
        this.host = this.requestURI.getHost();
        this.parameters.clear();
    }

    @Override
    public void setSession(Object session)
    {
        this.session = session;
    }

    @Override
    public void setSubProtocols(List<String> subProtocols)
    {
        this.subProtocols.clear();
        if (subProtocols != null)
        {
            this.subProtocols.addAll(subProtocols);
        }
    }

    /**
     * Set Sub Protocol request list.
     *
     * @param protocols the sub protocols desired
     */
    @Override
    public void setSubProtocols(String... protocols)
    {
        subProtocols.clear();
        Collections.addAll(subProtocols, protocols);
    }
}
