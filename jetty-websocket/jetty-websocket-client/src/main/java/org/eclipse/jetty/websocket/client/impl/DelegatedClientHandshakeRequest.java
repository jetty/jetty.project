//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.client.impl;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.net.HttpCookie;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.UrlEncoded;
import org.eclipse.jetty.websocket.common.HandshakeRequest;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClientUpgradeRequest;
import org.eclipse.jetty.websocket.core.extensions.ExtensionConfig;

/**
 * Representing the Jetty {@link org.eclipse.jetty.client.HttpClient}'s {@link org.eclipse.jetty.client.HttpRequest}
 * in the {@link org.eclipse.jetty.websocket.common.HandshakeRequest} interface.
 */
public class DelegatedClientHandshakeRequest implements HandshakeRequest
{
    private final WebSocketCoreClientUpgradeRequest delegate;
    private SocketAddress localSocketAddress;
    private SocketAddress remoteSocketAddress;

    public DelegatedClientHandshakeRequest(WebSocketCoreClientUpgradeRequest delegate)
    {
        this.delegate = delegate;
    }

    @Override
    public List<HttpCookie> getCookies()
    {
        return delegate.getCookies();
    }

    @Override
    public List<ExtensionConfig> getExtensions()
    {
        List<String> rawExtensions = delegate.getHeaders().getValuesList(HttpHeader.SEC_WEBSOCKET_EXTENSIONS);
        if (rawExtensions == null || rawExtensions.isEmpty())
            return Collections.emptyList();

        return rawExtensions.stream().map((parameterizedName) -> ExtensionConfig.parse(parameterizedName)).collect(Collectors.toList());
    }

    @Override
    public String getHeader(String name)
    {
        return delegate.getHeaders().get(name);
    }

    @Override
    public int getHeaderInt(String name)
    {
        HttpField field = delegate.getHeaders().getField(name);
        if (field == null)
            return -1;
        return field.getIntValue();
    }

    @Override
    public Map<String, List<String>> getHeadersMap()
    {
        Map<String, List<String>> ret = new HashMap<>();
        delegate.getHeaders().forEach((field) -> ret.put(field.getName(), Arrays.asList(field.getValues())));
        return ret;
    }

    @Override
    public List<String> getHeaders(String name)
    {
        return delegate.getHeaders().getValuesList(name);
    }

    @Override
    public String getHost()
    {
        return delegate.getHost();
    }

    @Override
    public String getHttpVersion()
    {
        return delegate.getVersion().toString();
    }

    @Override
    public SocketAddress getLocalSocketAddress()
    {
        return localSocketAddress;
    }

    @Override
    public SocketAddress getRemoteSocketAddress()
    {
        return remoteSocketAddress;
    }

    public void configure(EndPoint endpoint)
    {
        this.localSocketAddress = endpoint.getLocalAddress();
        this.remoteSocketAddress = endpoint.getRemoteAddress();
    }

    @Override
    public Locale getLocale()
    {
        HttpField field = delegate.getHeaders().getField("Locale");
        if (field == null)
            return null;
        String values[] = field.getValues();
        if (values == null || values.length < 1)
            return null;
        return new Locale(values[0]);
    }

    @Override
    public Enumeration<Locale> getLocales()
    {
        HttpField field = delegate.getHeaders().getField("Locale");
        if (field == null || field.getValues() == null)
            return Collections.emptyEnumeration();
        return Collections.enumeration(Arrays.stream(field.getValues())
                .map((s) -> new Locale(s))
                .collect(Collectors.toList()));
    }

    @Override
    public String getMethod()
    {
        return delegate.getMethod();
    }

    @Override
    public String getOrigin()
    {
        return delegate.getHeaders().get(HttpHeader.ORIGIN);
    }

    @Override
    public Map<String, List<String>> getParameterMap()
    {
        if (getQueryString() == null)
            return Collections.emptyMap();

        MultiMap<String> params = new MultiMap<>();
        UrlEncoded.decodeTo(getQueryString(), params, UTF_8);
        return params;
    }

    @Override
    public String getProtocolVersion()
    {
        return delegate.getHeaders().get(HttpHeader.SEC_WEBSOCKET_VERSION);
    }

    @Override
    public String getQueryString()
    {
        return delegate.getQuery();
    }

    @Override
    public URI getRequestURI()
    {
        return delegate.getURI();
    }

    @Override
    public List<String> getSubProtocols()
    {
        return delegate.getHeaders().getValuesList(HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL);
    }

    @Override
    public boolean hasSubProtocol(String test)
    {
        HttpField field = delegate.getHeaders().getField(HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL);
        if (field == null)
            return false;
        return field.contains(test);
    }

    @Override
    public boolean isSecure()
    {
        // TODO: figure out how to obtain from HttpClient's HttpRequest
        return false;
    }
}
