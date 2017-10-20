//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.common.handshake;

import java.net.HttpCookie;
import java.net.URI;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.core.extensions.ExtensionConfig;

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
    public Map<String, List<String>> getHeaderMap()
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
    public List<String> getSubProtocols()
    {
        return null;
    }

    @Override
    public boolean hasSubProtocol(String test)
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
    public void setHttpVersion(String httpVersion)
    {

    }

    @Override
    public void setMethod(String method)
    {

    }

    @Override
    public void setRequestURI(URI uri)
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
