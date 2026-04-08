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
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.eclipse.jetty.ee.servlet.ServletContextRequest;
import org.eclipse.jetty.ee.servlet.ServletContextResponse;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

/**
 * Almost an implementation of jaspi MessageInfo.
 */
public class JaspiMessageInfo implements MessageInfo
{
    public static final String AUTH_REQUEST_KEY = "jakarta.servlet.http.isAuthenticationRequest";
    public static final String AUTHENTICATION_TYPE_KEY = "jakarta.servlet.http.authType";
    public static final String MANDATORY_KEY = "jakarta.security.auth.message.MessagePolicy.isMandatory";
    private final Callback _callback;
    private Request _request;
    private Response _response;
    private final Map<String, Object> _map = new HashMap<>();

    public JaspiMessageInfo(Request request, Response response, Callback callback)
    {
        _request = request;
        _response = response;
        _callback = callback;
    }
    
    public Callback getCallback()
    {
        return _callback;
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
        ServletContextRequest inContext = Request.asInContext(_request, ServletContextRequest.class);
        return inContext == null ? null : inContext.getServletApiRequest();
    }

    @Override
    public Object getResponseMessage()
    {
        ServletContextResponse inContext = Response.asInContext(_response, ServletContextResponse.class);
        return inContext == null ? null : inContext.getServletApiResponse();
    }

    @Override
    public void setRequestMessage(Object request)
    {
        if (!(request instanceof ServletRequest))
            throw new IllegalStateException("Not a ServletRequest");
        _request = ServletContextRequest.getServletContextRequest((ServletRequest)request);
    }

    @Override
    public void setResponseMessage(Object response)
    {
        if (!(response instanceof ServletResponse))
            throw new IllegalStateException("Not a ServletResponse");
        _response = ServletContextResponse.getServletContextResponse((ServletResponse)response);
    }
}
