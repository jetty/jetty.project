//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356.server;

import java.net.URI;
import java.security.Principal;
import java.util.List;
import java.util.Map;

import javax.websocket.server.HandshakeRequest;

import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;

public class JsrHandshakeRequest implements HandshakeRequest
{
    private final ServletUpgradeRequest request;

    public JsrHandshakeRequest(ServletUpgradeRequest req)
    {
        this.request = req;
    }

    @Override
    public Map<String, List<String>> getHeaders()
    {
        return request.getHeaders();
    }

    @Override
    public Object getHttpSession()
    {
        return request.getSession();
    }

    @Override
    public Map<String, List<String>> getParameterMap()
    {
        return request.getParameterMap();
    }

    @Override
    public String getQueryString()
    {
        return request.getQueryString();
    }

    public PathSpec getRequestPathSpec()
    {
        return (PathSpec)request.getServletAttribute(PathSpec.class.getName());
    }

    @Override
    public URI getRequestURI()
    {
        return request.getRequestURI();
    }

    @Override
    public Principal getUserPrincipal()
    {
        return request.getUserPrincipal();
    }

    @Override
    public boolean isUserInRole(String role)
    {
        return request.isUserInRole(role);
    }
}
