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

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.jetty.client.HttpResponse;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.websocket.common.HandshakeResponse;
import org.eclipse.jetty.websocket.core.extensions.ExtensionConfig;

/**
 * Representing the Jetty {@link org.eclipse.jetty.client.HttpClient}'s {@link org.eclipse.jetty.client.HttpResponse}
 * in the {@link org.eclipse.jetty.websocket.common.HandshakeResponse} interface.
 */
public class DelegatedClientHandshakeResponse implements HandshakeResponse
{
    private HttpResponse delegate;

    public DelegatedClientHandshakeResponse(HttpResponse response)
    {
        this.delegate = response;
    }

    @Override
    public String getAcceptedSubProtocol()
    {
        return this.delegate.getHeaders().get(HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL);
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
        return this.delegate.getHeaders().get(name);
    }

    @Override
    public Set<String> getHeaderNames()
    {
        return delegate.getHeaders().getFieldNamesCollection();
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
        return this.delegate.getHeaders().getValuesList(name);
    }

    @Override
    public int getStatusCode()
    {
        return this.delegate.getStatus();
    }
}
