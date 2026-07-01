//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee.security.jaspi;

import java.util.HashMap;
import java.util.Map;

import jakarta.security.auth.message.MessageInfo;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee.servlet.ServletContextRequest;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;

/**
 * Almost an implementation of jaspi MessageInfo.
 */
public class JaspiMessageInfo implements MessageInfo
{
    public static final String AUTH_REQUEST_KEY = "jakarta.servlet.http.isAuthenticationRequest";
    public static final String AUTHENTICATION_TYPE_KEY = "jakarta.servlet.http.authType";
    public static final String MANDATORY_KEY = "jakarta.security.auth.message.MessagePolicy.isMandatory";
    public static final String REGISTER_SESSION_KEY = "jakarta.servlet.http.registerSession";
    private final Map<String, Object> _map = new HashMap<>();
    private final Request _request;
    private final Response _response;
    private HttpServletRequest _httpServletRequest;
    private HttpServletResponse _httpServletResponse;

    public JaspiMessageInfo(Request request, Response response)
    {
        _request = request;
        _response = response;

        ServletContextRequest servletContextRequest = Request.asInContext(_request, ServletContextRequest.class);
        if (servletContextRequest == null)
            throw new IllegalStateException("ServletContextRequest is null");
        _httpServletRequest = servletContextRequest.getHttpServletRequest();
        _httpServletResponse = servletContextRequest.getHttpServletResponse();
    }

    @Override
    public Map<String, Object> getMap()
    {
        return _map;
    }

    public Request getBaseRequest()
    {
        return _request;
    }
    
    public Response getBaseResponse()
    {
        return _response;
    }

    public String getAuthenticationType()
    {
        return (String)_map.get(AUTHENTICATION_TYPE_KEY);
    }

    public void setMandatory(boolean isMandatory)
    {
        if (isMandatory)
            _map.put(JaspiMessageInfo.MANDATORY_KEY, "true");
        else
            _map.remove(JaspiMessageInfo.MANDATORY_KEY);
    }

    public void setAuthenticationRequest(boolean isAuthenticationRequest)
    {
        if (isAuthenticationRequest)
            _map.put(JaspiMessageInfo.AUTH_REQUEST_KEY, "true");
        else
            _map.remove(JaspiMessageInfo.AUTH_REQUEST_KEY);
    }

    @Override
    public Object getRequestMessage()
    {
        return _httpServletRequest;
    }

    @Override
    public Object getResponseMessage()
    {
        return _httpServletResponse;
    }

    @Override
    public void setRequestMessage(Object request)
    {
        if (request instanceof HttpServletRequest httpServletRequest)
            _httpServletRequest = httpServletRequest;
        else
            throw new IllegalStateException("Not an HttpServletRequest");
    }

    @Override
    public void setResponseMessage(Object response)
    {
        if (response instanceof HttpServletResponse httpServletResponse)
            _httpServletResponse = httpServletResponse;
        else
            throw new IllegalStateException("Not an HttpServletResponse");
    }
}
