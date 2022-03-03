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

package org.eclipse.jetty.ee10.websocket.client.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.jetty.client.HttpResponse;
import org.eclipse.jetty.ee10.websocket.api.ExtensionConfig;
import org.eclipse.jetty.ee10.websocket.api.UpgradeResponse;
import org.eclipse.jetty.http.HttpHeader;

/**
 * Representing the Jetty {@link org.eclipse.jetty.client.HttpClient}'s {@link org.eclipse.jetty.client.HttpResponse}
 * in the {@link UpgradeResponse} interface.
 */
public class DelegatedJettyClientUpgradeResponse implements UpgradeResponse
{
    private final HttpResponse delegate;

    public DelegatedJettyClientUpgradeResponse(HttpResponse response)
    {
        this.delegate = response;
    }

    @Override
    public String getAcceptedSubProtocol()
    {
        return this.delegate.getHeaders().get(HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL);
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
    public List<String> getHeaders(String name)
    {
        return this.delegate.getHeaders().getValuesList(name);
    }

    @Override
    public Map<String, List<String>> getHeaders()
    {
        Map<String, List<String>> headers = getHeaderNames().stream()
            .collect(Collectors.toMap((name) -> name, (name) -> new ArrayList<>(getHeaders(name))));
        return Collections.unmodifiableMap(headers);
    }

    @Override
    public int getStatusCode()
    {
        return this.delegate.getStatus();
    }

    @Override
    public List<ExtensionConfig> getExtensions()
    {
        List<String> rawExtensions = delegate.getHeaders().getValuesList(HttpHeader.SEC_WEBSOCKET_EXTENSIONS);
        if (rawExtensions == null || rawExtensions.isEmpty())
            return Collections.emptyList();

        return rawExtensions.stream().map(ExtensionConfig::parse).collect(Collectors.toList());
    }
}
