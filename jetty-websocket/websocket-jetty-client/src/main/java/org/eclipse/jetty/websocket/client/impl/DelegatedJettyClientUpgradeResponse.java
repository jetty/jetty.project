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

package org.eclipse.jetty.websocket.client.impl;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.jetty.client.HttpResponse;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.websocket.api.UpgradeResponse;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;

/**
 * Representing the Jetty {@link org.eclipse.jetty.client.HttpClient}'s {@link org.eclipse.jetty.client.HttpResponse}
 * in the {@link UpgradeResponse} interface.
 */
public class DelegatedJettyClientUpgradeResponse implements UpgradeResponse
{
    private HttpResponse delegate;

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
        return null;
    }

    @Override
    public int getStatusCode()
    {
        return this.delegate.getStatus();
    }

    @Override
    public void addHeader(String name, String value)
    {

    }

    @Override
    public void sendForbidden(String message) throws IOException
    {

    }

    @Override
    public void setAcceptedSubProtocol(String protocol)
    {

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
    public void setExtensions(List<org.eclipse.jetty.websocket.api.extensions.ExtensionConfig> extensions)
    {

    }

    @Override
    public void setHeader(String name, String value)
    {

    }

    @Override
    public void setStatusCode(int statusCode)
    {

    }
}
