//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.common.handshake;

import java.net.HttpCookie;
import java.net.URI;
import java.security.Principal;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;

public class DummyUpgradeRequest implements UpgradeRequest
{
    @Override
    public void addExtensions(ExtensionConfig... configs)
    {

    }

    @Override
    public void addExtensions(String... configs)
    {

    }

    @Override
    public List<HttpCookie> getCookies()
    {
        return null;
    }

    @Override
    public List<ExtensionConfig> getExtensions()
    {
        return null;
    }

    @Override
    public String getHeader(String name)
    {
        return null;
    }

    @Override
    public int getHeaderInt(String name)
    {
        return 0;
    }

    @Override
    public Map<String, List<String>> getHeaders()
    {
        return null;
    }

    @Override
    public List<String> getHeaders(String name)
    {
        return null;
    }

    @Override
    public String getHost()
    {
        return null;
    }

    @Override
    public String getHttpVersion()
    {
        return null;
    }

    @Override
    public String getMethod()
    {
        return null;
    }

    @Override
    public String getOrigin()
    {
        return null;
    }

    @Override
    public Map<String, List<String>> getParameterMap()
    {
        return null;
    }

    @Override
    public String getProtocolVersion()
    {
        return null;
    }

    @Override
    public String getQueryString()
    {
        return null;
    }

    @Override
    public URI getRequestURI()
    {
        return null;
    }

    @Override
    public Object getSession()
    {
        return null;
    }

    @Override
    public List<String> getSubProtocols()
    {
        return null;
    }

    @Override
    public Principal getUserPrincipal()
    {
        return null;
    }

    @Override
    public boolean hasSubProtocol(String test)
    {
        return false;
    }

    @Override
    public boolean isSecure()
    {
        return false;
    }

    @Override
    public void setCookies(List<HttpCookie> cookies)
    {

    }

    @Override
    public void setExtensions(List<ExtensionConfig> configs)
    {

    }

    @Override
    public void setHeader(String name, List<String> values)
    {

    }

    @Override
    public void setHeader(String name, String value)
    {

    }

    @Override
    public void setHeaders(Map<String, List<String>> headers)
    {

    }

    @Override
    public void setSession(Object session)
    {

    }

    @Override
    public void setSubProtocols(List<String> protocols)
    {

    }

    @Override
    public void setSubProtocols(String... protocols)
    {

    }
}
