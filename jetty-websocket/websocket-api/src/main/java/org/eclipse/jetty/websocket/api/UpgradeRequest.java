//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.api;

import java.net.URI;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.net.websocket.HandshakeRequest;

import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;

public class UpgradeRequest implements HandshakeRequest
{
    private URI requestURI;
    private List<String> subProtocols = new ArrayList<>();
    private Object session;

    protected UpgradeRequest()
    {
        /* anonymous, no requestURI, upgrade request */
    }

    public UpgradeRequest(String requestURI)
    {
        this.requestURI = URI.create(requestURI);
    }

    public UpgradeRequest(URI requestURI)
    {
        this.requestURI = requestURI;
    }

    public void addExtensions(String... extConfigs)
    {
        // TODO Auto-generated method stub
    }

    public List<ExtensionConfig> getExtensions()
    {
        // TODO Auto-generated method stub
        return null;
    }

    public String getHeader(String name)
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Map<String, List<String>> getHeaders()
    {
        // TODO Auto-generated method stub
        return null;
    }

    public String getHost()
    {
        return getHeader("Host");
    }

    public String getHttpVersion()
    {
        // TODO Auto-generated method stub
        return null;
    }

    public String getMethod()
    {
        // TODO Auto-generated method stub
        return null;
    }

    public String getOrigin()
    {
        return getHeader("Origin");
    }

    @Override
    public Map<String, String[]> getParameterMap()
    {
        // TODO Auto-generated method stub
        return null;
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
        return session;
    }

    public List<String> getSubProtocols()
    {
        return subProtocols;
    }

    @Override
    public Principal getUserPrincipal()
    {
        // TODO Auto-generated method stub
        return null;
    }

    public boolean hasSubProtocol(String test)
    {
        return subProtocols.contains(test);
    }

    public boolean isOrigin(String test)
    {
        return test.equalsIgnoreCase(getOrigin());
    }

    @Override
    public boolean isUserInRole(String role)
    {
        // TODO Auto-generated method stub
        return false;
    }

    public void setSession(Object session)
    {
        this.session = session;
    }

    public void setSubProtocols(List<String> subProtocols)
    {
        this.subProtocols = subProtocols;
    }

    public void setSubProtocols(String protocols)
    {
        // TODO Auto-generated method stub
    }
}
