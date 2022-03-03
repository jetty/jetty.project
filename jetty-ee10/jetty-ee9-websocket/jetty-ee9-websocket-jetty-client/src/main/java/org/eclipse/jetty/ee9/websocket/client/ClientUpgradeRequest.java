//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.websocket.client;

import java.net.HttpCookie;
import java.net.URI;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.ee9.websocket.api.ExtensionConfig;
import org.eclipse.jetty.ee9.websocket.api.UpgradeRequest;
import org.eclipse.jetty.ee9.websocket.api.UpgradeResponse;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpScheme;

/**
 * Client based UpgradeRequest API
 */
public final class ClientUpgradeRequest implements UpgradeRequest
{
    private final List<String> subProtocols = new ArrayList<>(1);
    private final List<ExtensionConfig> extensions = new ArrayList<>(1);
    private final List<HttpCookie> cookies = new ArrayList<>(1);
    private final Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final URI requestURI;
    private final String host;
    private long timeout;

    public ClientUpgradeRequest()
    {
        /* anonymous, no requestURI, upgrade request */
        this.requestURI = null;
        this.host = null;
    }

    /**
     * @deprecated use {@link #ClientUpgradeRequest()} instead.
     */
    @Deprecated
    public ClientUpgradeRequest(URI uri)
    {
        this.requestURI = uri;
        String scheme = uri.getScheme();
        if (!HttpScheme.WS.is(scheme) && !HttpScheme.WSS.is(scheme))
            throw new IllegalArgumentException("URI scheme must be 'ws' or 'wss'");
        this.host = this.requestURI.getHost();
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
        throw new UnsupportedOperationException("HttpVersion not available on ClientUpgradeRequest");
    }

    @Override
    public String getMethod()
    {
        throw new UnsupportedOperationException("Method not available on ClientUpgradeRequest");
    }

    @Override
    public String getOrigin()
    {
        return getHeader(HttpHeader.ORIGIN.name());
    }

    @Override
    public Map<String, List<String>> getParameterMap()
    {
        return Collections.emptyMap();
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
        return requestURI == null ? null : requestURI.getQuery();
    }

    @Override
    public URI getRequestURI()
    {
        return requestURI;
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

    /**
     * Add WebSocket Extension Configuration(s) to Upgrade Request.
     * <p>
     * This is merely the list of requested Extensions to use, see {@link UpgradeResponse#getExtensions()} for what was
     * negotiated
     *
     * @param configs the configuration(s) to add
     */
    public void addExtensions(ExtensionConfig... configs)
    {
        Collections.addAll(extensions, configs);
    }

    /**
     * Add WebSocket Extension Configuration(s) to request
     * <p>
     * This is merely the list of requested Extensions to use, see {@link UpgradeResponse#getExtensions()} for what was
     * negotiated
     *
     * @param configs the configuration(s) to add
     */
    public void addExtensions(String... configs)
    {
        for (String config : configs)
        {
            extensions.add(ExtensionConfig.parse(config));
        }
    }

    /**
     * Set the list of Cookies on the request
     *
     * @param cookies the cookies to use
     */
    public void setCookies(List<HttpCookie> cookies)
    {
        this.cookies.clear();
        if (cookies != null && !cookies.isEmpty())
        {
            this.cookies.addAll(cookies);
        }
    }

    /**
     * Set the list of WebSocket Extension configurations on the request.
     *
     * @param configs the list of extension configurations
     */
    public void setExtensions(List<ExtensionConfig> configs)
    {
        this.extensions.clear();
        if (configs != null)
        {
            this.extensions.addAll(configs);
        }
    }

    /**
     * Set a specific header with multi-value field
     * <p>
     * Overrides any previous value for this named header
     *
     * @param name the name of the header
     * @param values the multi-value field
     */
    public void setHeader(String name, List<String> values)
    {
        headers.put(name, values);
    }

    /**
     * Set a specific header value
     * <p>
     * Overrides any previous value for this named header
     *
     * @param name the header to set
     * @param value the value to set it to
     */
    public void setHeader(String name, String value)
    {
        List<String> values = new ArrayList<>();
        values.add(value);
        setHeader(name, values);
    }

    /**
     * Sets multiple headers on the request.
     * <p>
     * Only sets those headers provided, does not remove
     * headers that exist on request and are not provided in the
     * parameter for this method.
     * <p>
     * Convenience method vs calling {@link #setHeader(String, List)} multiple times.
     *
     * @param headers the headers to set
     */
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

    /**
     * Set the offered WebSocket Sub-Protocol list.
     *
     * @param protocols the offered sub-protocol list
     */
    public void setSubProtocols(List<String> protocols)
    {
        this.subProtocols.clear();
        if (protocols != null)
        {
            this.subProtocols.addAll(protocols);
        }
    }

    /**
     * Set the offered WebSocket Sub-Protocol list.
     *
     * @param protocols the offered sub-protocol list
     */
    public void setSubProtocols(String... protocols)
    {
        subProtocols.clear();
        Collections.addAll(subProtocols, protocols);
    }

    /**
     * @param timeout the total timeout for the request/response conversation of the WebSocket handshake;
     * use zero or a negative value to disable the timeout
     * @param unit the timeout unit
     */
    public void setTimeout(long timeout, TimeUnit unit)
    {
        this.timeout = unit.toMillis(timeout);
    }

    /**
     * @return the total timeout for this request, in milliseconds;
     * zero or negative if the timeout is disabled
     */
    public long getTimeout()
    {
        return timeout;
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
