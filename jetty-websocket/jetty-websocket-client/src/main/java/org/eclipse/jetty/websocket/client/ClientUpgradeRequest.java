//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.client;

import java.net.HttpCookie;
import java.net.URI;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;

/**
 * Client based UpgradeRequest API
 */
public final class ClientUpgradeRequest implements UpgradeRequest
{
    private URI requestURI;
    private List<String> subProtocols = new ArrayList<>(1);
    private List<ExtensionConfig> extensions = new ArrayList<>(1);
    private List<HttpCookie> cookies = new ArrayList<>(1);
    private Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private Map<String, List<String>> parameters = new HashMap<>(1);
    private String httpVersion;
    private String method;
    private String host;

    public ClientUpgradeRequest()
    {
        /* anonymous, no requestURI, upgrade request */
    }

    public ClientUpgradeRequest(URI uri)
    {
        this.requestURI = uri;
        String scheme = uri.getScheme();
        if (!HttpScheme.WS.is(scheme) || !HttpScheme.WSS.is(scheme))
            throw new IllegalArgumentException("URI scheme must be 'ws' or 'wss'");
        this.host = this.requestURI.getHost();
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
    public List<HttpCookie> getCookies()
    {
        return cookies;
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
    public List<ExtensionConfig> getExtensions()
    {
        return extensions;
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
    public String getHeader(String name)
    {
        List<String> values = headers.get(name);
        return joinValues(values);
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
        return getHeader(HttpHeader.ORIGIN.name());
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
        return Objects.requireNonNullElse(version, "13");
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

    @Override
    public Object getSession()
    {
        throw new UnsupportedOperationException("HttpSession not available on Client request");
    }

    @Override
    public List<String> getSubProtocols()
    {
        return subProtocols;
    }

    @Override
    public Principal getUserPrincipal()
    {
        throw new UnsupportedOperationException("User Principal not available on Client request");
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

    @Override
    public void setSubProtocols(List<String> subProtocols)
    {
        this.subProtocols.clear();
        if (subProtocols != null)
        {
            this.subProtocols.addAll(subProtocols);
        }
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
    public boolean isSecure()
    {
        throw new UnsupportedOperationException("Request.isSecure not available on Client request");
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
        this.headers.clear();
        for (Map.Entry<String, List<String>> entry : headers.entrySet())
        {
            String name = entry.getKey();
            List<String> values = entry.getValue();
            setHeader(name, values);
        }
    }

    @Override
    public void setSession(Object session)
    {
        throw new UnsupportedOperationException("HttpSession not available on Client request");
    }

    /**
     * ABNF from RFC 2616, RFC 822, and RFC 6455 specified characters requiring quoting.
     */
    public static final String ABNF_REQUIRED_QUOTING = "\"'\\\n\r\t\f\b%+ ;=";

    public static String joinValues(List<String> values)
    {
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
            quoteIfNeeded(ret, value, ABNF_REQUIRED_QUOTING);
            needsDelim = true;
        }
        return ret.toString();
    }

    /**
     * Append into buf the provided string, adding quotes if needed.
     * <p>
     * Quoting is determined if any of the characters in the {@code delim} are found in the input {@code str}.
     *
     * @param buf the buffer to append to
     * @param str the string to possibly quote
     * @param delim the delimiter characters that will trigger automatic quoting
     */
    private static void quoteIfNeeded(StringBuilder buf, String str, String delim)
    {
        if (str == null)
        {
            return;
        }
        // check for delimiters in input string
        int len = str.length();
        if (len == 0)
        {
            return;
        }
        int ch;
        for (int i = 0; i < len; i++)
        {
            ch = str.codePointAt(i);
            if (delim.indexOf(ch) >= 0)
            {
                // found a delimiter codepoint. we need to quote it.
                buf.append('"');
                buf.append(str);
                buf.append('"');
                return;
            }
        }

        // no special delimiters used, no quote needed.
        buf.append(str);
    }
}
